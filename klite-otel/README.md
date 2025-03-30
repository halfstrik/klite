# klite-otel

An experimental module to provide instrumentation for OpenTelemetry (aka OTeL)

By default, OpenTelemetry agent should be able to report JVM metrics as well as JDBC-related activity
However it won't be able to properly handle HTTP request/response instrumentation

Work in progress.

Kinda following implementation for [http4k](https://github.com/http4k/http4k/tree/master/core/ops/opentelemetry)
