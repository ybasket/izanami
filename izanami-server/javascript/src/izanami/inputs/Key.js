import React from "react";

const Key = props => {
  const values = props.value.split(":").filter(e => !!e);
  return (
    <div
      className="btn-group btn-breadcrumb"
      data-toggle="tooltip"
      data-placement="top"
      title={props.value}
    >
      {values.map((part, i) => (
        <div className="key-value-value" key={`key-value-${props.value}-${i}`}>
          <span>{part}</span>
          {i < values.length - 1 && <i className="fas fa-caret-right" />}
        </div>
      ))}
    </div>
  );
};

export { Key };
