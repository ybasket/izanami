package domains.script

import akka.{Done, NotUsed}
import akka.stream.scaladsl.{Flow, Source}
import cats.data.EitherT
import cats.effect.{Async, Effect}
import domains.events.EventStore
import domains.events.Events.{GlobalScriptCreated, IzanamiEvent}
import domains.script.GlobalScript.GlobalScriptKey
import domains.{ImportResult, Key}
import env.Env
import libs.functional.EitherTSyntax
import libs.logs.IzanamiLogger
import play.api.libs.json._
import store.Result.Result
import store._

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

sealed trait Script
final case class JavascriptScript(script: String) extends Script
final case class ScalaScript(script: String)      extends Script
final case class KotlinScript(script: String)     extends Script

sealed trait ScriptExecution
final case class ScriptExecutionSuccess(result: Boolean, logs: Seq[String] = Seq.empty) extends ScriptExecution
final case class ScriptExecutionFailure(logs: Seq[String] = Seq.empty, stacktrace: Seq[String] = Seq.empty)
    extends ScriptExecution

object ScriptExecutionFailure {
  def fromThrowable(logs: Seq[String] = Seq.empty, e: Throwable): ScriptExecutionFailure =
    ScriptExecutionFailure(
      logs,
      Option(e.getCause).toSeq.flatMap { c =>
        Seq(c.toString) ++ c.getStackTrace.map(stackElt => s"  ${stackElt.toString}")
      } ++ Seq(e.toString) ++ e.getStackTrace.map(stackElt => s"  ${stackElt.toString}")
    )
}

object Script {

  type ScriptCache[F[_]] = CacheService[F, String]

  object ScriptCache {
    def apply[F[_]](implicit s: ScriptCache[F]): ScriptCache[F] = s
  }

}

case class ScriptLogs(logs: Seq[String] = Seq.empty)

trait RunnableScript[F[_], S] {
  def run(script: S, context: JsObject, env: Env): F[ScriptExecution]
}

object syntax {
  implicit class RunnableScriptOps[S](script: S) {
    def run[F[_]](context: JsObject, env: Env)(implicit runnableScript: RunnableScript[F, S]): F[ScriptExecution] =
      runnableScript.run(script, context, env)
  }
}

case class GlobalScript(id: Key, name: String, description: String, source: Script)

object GlobalScript {
  type GlobalScriptKey = Key
}

trait GlobalScriptService[F[_]] {
  def create(id: GlobalScriptKey, data: GlobalScript): F[Result[GlobalScript]]
  def update(oldId: GlobalScriptKey, id: GlobalScriptKey, data: GlobalScript): F[Result[GlobalScript]]
  def delete(id: GlobalScriptKey): F[Result[GlobalScript]]
  def deleteAll(query: Query): F[Result[Done]]
  def getById(id: GlobalScriptKey): F[Option[GlobalScript]]
  def findByQuery(query: Query, page: Int = 1, nbElementPerPage: Int = 15): F[PagingResult[GlobalScript]]
  def findByQuery(query: Query): Source[(GlobalScriptKey, GlobalScript), NotUsed]
  def count(query: Query): F[Long]
  def importData(implicit ec: ExecutionContext): Flow[(String, JsValue), ImportResult, NotUsed]
}

trait CacheService[F[_], K] {

  def get[T: ClassTag](id: K): F[Option[T]]

  def set[T: ClassTag](id: K, value: T): F[Unit]

}

object GlobalScriptService {

  val eventAdapter = Flow[IzanamiEvent].collect {
    case GlobalScriptCreated(key, script, _, _) =>
  }

}

class GlobalScriptServiceImpl[F[_]: Effect](jsonStore: JsonDataStore[F], eventStore: EventStore[F])
    extends GlobalScriptService[F]
    with EitherTSyntax[F] {

  import ScriptInstances._
  import libs.streams.syntax._
  import GlobalScript._
  import GlobalScriptInstances._
  import cats.implicits._
  import store.Result._
  import libs.functional.syntax._
  import domains.events.Events._

  override def create(id: GlobalScriptKey, data: GlobalScript): F[Result[GlobalScript]] = {
    // format: off
    val r: EitherT[F, AppErrors, GlobalScript] = for {
      created     <- jsonStore.create(id, GlobalScriptInstances.format.writes(data))   |> liftFEither
      apikey      <- created.validate[GlobalScript]                           |> liftJsResult{ handleJsError }
      _           <- eventStore.publish(GlobalScriptCreated(id, apikey))      |> liftF[AppErrors, Done]
    } yield apikey
    // format: on
    r.value
  }

  override def update(oldId: GlobalScriptKey, id: GlobalScriptKey, data: GlobalScript): F[Result[GlobalScript]] = {
    // format: off
    val r: EitherT[F, AppErrors, GlobalScript] = for {
      oldValue    <- getById(oldId)                                                     |> liftFOption(AppErrors.error("error.data.missing", oldId.key))
      updated     <- jsonStore.update(oldId, id, GlobalScriptInstances.format.writes(data))      |> liftFEither
      apikey      <- updated.validate[GlobalScript]                                     |> liftJsResult{ handleJsError }
      _           <- eventStore.publish(GlobalScriptUpdated(id, oldValue, apikey))      |> liftF[AppErrors, Done]
    } yield apikey
    // format: on
    r.value
  }

  override def delete(id: GlobalScriptKey): F[Result[GlobalScript]] = {
    // format: off
    val r: EitherT[F, AppErrors, GlobalScript] = for {
      deleted <- jsonStore.delete(id)                                   |> liftFEither
      apikey  <- deleted.validate[GlobalScript]                         |> liftJsResult{ handleJsError }
      _       <- eventStore.publish(GlobalScriptDeleted(id, apikey))    |> liftF[AppErrors, Done]
    } yield apikey
    // format: on
    r.value
  }

  override def deleteAll(query: Query): F[Result[Done]] =
    jsonStore.deleteAll(query)

  override def getById(id: GlobalScriptKey): F[Option[GlobalScript]] =
    jsonStore.getById(id).map(_.flatMap(_.validate[GlobalScript].asOpt))

  override def findByQuery(query: Query, page: Int, nbElementPerPage: Int): F[PagingResult[GlobalScript]] =
    jsonStore
      .findByQuery(query, page, nbElementPerPage)
      .map(jsons => JsonPagingResult(jsons))

  override def findByQuery(query: Query): Source[(Key, GlobalScript), NotUsed] =
    jsonStore.findByQuery(query).readsKV[GlobalScript]

  override def count(query: Query): F[Long] =
    jsonStore.count(query)

  override def importData(implicit ec: ExecutionContext): Flow[(String, JsValue), ImportResult, NotUsed] = {
    import cats.implicits._
    import libs.streams.syntax._
    import store.Result.AppErrors._

    Flow[(String, JsValue)]
      .map { case (s, json) => (s, GlobalScriptInstances.format.reads(json)) }
      .mapAsyncF(4) {
        case (_, JsSuccess(obj, _)) =>
          create(obj.id, obj) map { ImportResult.fromResult _ }
        case (s, JsError(_)) =>
          Effect[F].pure(ImportResult.error(ErrorMessage("json.parse.error", s)))
      }
      .fold(ImportResult()) { _ |+| _ }
  }

  private def handleJsError(err: Seq[(JsPath, Seq[JsonValidationError])]): AppErrors = {
    IzanamiLogger.error(s"Error parsing json from database $err")
    AppErrors.error("error.json.parsing")
  }
}
