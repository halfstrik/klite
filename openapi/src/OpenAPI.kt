package klite.openapi

import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Schema.AccessMode
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import klite.*
import klite.RequestMethod.GET
import klite.StatusCode.Companion.NoContent
import klite.StatusCode.Companion.OK
import klite.annotations.*
import java.math.BigDecimal
import java.net.URI
import java.net.URL
import java.time.*
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KParameter.Kind.INSTANCE
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.createType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor

// Spec: https://swagger.io/specification/
// Sample: https://github.com/OAI/OpenAPI-Specification/blob/main/examples/v3.0/api-with-examples.json

/**
 * Adds an /openapi endpoint to the context, listing all the routes.
 * - Use @Operation swagger annotation to describe the routes
 * - Use @Hidden to hide a route from the spec
 * - @Parameter annotation can be used on method parameters directly.
 * - @Tag annotation is supported on route classes for grouping of routes.
 */
fun Router.openApi(path: String = "/openapi", title: String = "API", version: String = "1.0.0", annotations: List<Annotation> = emptyList()) {
  add(Route(GET, path.toRegex(), annotations) {
    mapOf(
      "openapi" to "3.0.0",
      "info" to mapOf("title" to title, "version" to version),
      "servers" to listOf(mapOf("url" to fullUrl(prefix))),
      "tags" to toTags(routes),
      "paths" to routes.filter { !it.hasAnnotation<Hidden>() }.groupBy { pathParamRegexer.toOpenApi(it.path) }.mapValues { (_, routes) ->
        routes.associate(::toOperation)
      }
    )
  })
}

internal fun toTags(routes: List<Route>) = routes.asSequence()
  .map { it.handler }
  .filterIsInstance<FunHandler>()
  .map { it.instance::class.annotation<Tag>()?.toNonEmptyValues() ?: mapOf("name" to it.instance::class.simpleName) }
  .toSet()

internal fun toOperation(route: Route): Pair<String, Any> {
  val op = route.annotation<Operation>()
  val funHandler = route.handler as? FunHandler
  return (op?.method?.trimToNull() ?: route.method.name).lowercase() to mapOf(
    "operationId" to route.handler.let { (if (it is FunHandler) it.instance::class.simpleName + "." + it.f.name else it::class.simpleName) },
    "tags" to listOfNotNull(funHandler?.let { it.instance::class.annotation<Tag>()?.name ?: it.instance::class.simpleName }),
    "parameters" to funHandler?.let {
      it.params.filter { it.source != null }.map { p -> toParameter(p, op) }
    },
    "requestBody" to toRequestBody(route, route.annotation<RequestBody>() ?: op?.requestBody),
    "responses" to toResponsesByCode(route, op, funHandler?.f?.returnType)
  ) + (op?.let { it.toNonEmptyValues { it.name !in setOf("method", "requestBody", "responses") } } ?: emptyMap())
}

fun toParameter(p: Param, op: Operation? = null) = mapOf(
  "name" to p.name,
  "required" to (!p.p.isOptional && !p.p.type.isMarkedNullable),
  "in" to toParameterIn(p.source),
  "schema" to p.p.type.toJsonSchema(response = true),
) + ((p.p.findAnnotation<Parameter>() ?: op?.parameters?.find { it.name == p.name })?.toNonEmptyValues() ?: emptyMap())

private fun toParameterIn(paramAnnotation: Annotation?) = when(paramAnnotation) {
  is HeaderParam -> ParameterIn.HEADER
  is QueryParam -> ParameterIn.QUERY
  is PathParam -> ParameterIn.PATH
  is CookieParam -> ParameterIn.COOKIE
  else -> null
}

private fun KType.toJsonSchema(response: Boolean = false): Map<String, Any>? {
  val cls = classifier as? KClass<*> ?: return null
  val jsonType = when (cls) {
    Nothing::class -> "null"
    Boolean::class -> "boolean"
    Number::class -> "integer"
    BigDecimal::class, Decimal::class, Float::class, Double::class -> "number"
    else -> if (cls == String::class || Converter.supports(cls)) "string" else "object"
  }
  val jsonFormat = when (cls) {
    LocalDate::class, Date::class -> "date"
    LocalTime::class -> "time"
    Instant::class, LocalDateTime::class -> "date-time"
    Period::class, Duration::class -> "duration"
    URI::class, URL::class -> "uri"
    UUID::class -> "uuid"
    else -> null
  }
  return mapOfNotNull(
    "type" to jsonType,
    "format" to jsonFormat,
    "enum" to if (cls.isSubclassOf(Enum::class)) cls.java.enumConstants.toList() else null,
    "properties" to if (jsonType == "object") cls.publicProperties.associate { it.name to it.returnType.toJsonSchema(response) }.takeIf { it.isNotEmpty() } else null,
    "required" to if (jsonType == "object") cls.publicProperties.filter { p ->
      !p.returnType.isMarkedNullable && (response || cls.primaryConstructor?.parameters?.find { it.name == p.name }?.isOptional != true)
    }.map { it.name }.toSet().takeIf { it.isNotEmpty() } else null
  )
}

private fun toRequestBody(route: Route, annotation: RequestBody?): Map<String, Any?>? {
  val bodyParam = (route.handler as? FunHandler)?.params?.find { it.p.kind != INSTANCE && it.source == null && it.cls.java.packageName != "klite" }?.p
  val requestBody = annotation?.toNonEmptyValues() ?: HashMap()
  if (annotation != null && annotation.content.isNotEmpty())
    requestBody["content"] = annotation.content.associate {
      val content = it.toNonEmptyValues { it.name != "mediaType" }
      if (it.schema.implementation != Void::class.java) content["schema"] = it.schema.implementation.createType().toJsonSchema()
      it.mediaType to content
    }
  if (bodyParam != null) requestBody.putIfAbsent("content", bodyParam.type.toJsonContent())
  if (requestBody.isEmpty()) return null
  requestBody.putIfAbsent("required", bodyParam == null || !bodyParam.isOptional)
  return requestBody
}

private fun toResponsesByCode(route: Route, op: Operation?, returnType: KType?): Map<StatusCode, Any?> {
  val responses = LinkedHashMap<StatusCode, Any?>()
  if (returnType?.classifier == Unit::class) responses[NoContent] = mapOf("description" to "No content")
  else if (op?.responses?.isEmpty() != false) responses[OK] = mapOfNotNull("description" to "OK", "content" to returnType?.toJsonContent(response = true))
  (route.annotations.filterIsInstance<ApiResponse>() + (route.annotation<ApiResponses>()?.value ?: emptyArray()) + (op?.responses ?: emptyArray())).forEach {
    responses[StatusCode(it.responseCode.toInt())] = it.toNonEmptyValues { it.name != "responseCode" }
  }
  return responses
}

private fun KType.toJsonContent(response: Boolean = false) = mapOf(MimeTypes.json to mapOf("schema" to toJsonSchema(response)))

internal fun <T: Annotation> T.toNonEmptyValues(filter: (KProperty1<T, *>) -> Boolean = { true }): MutableMap<String, Any?> = HashMap<String, Any?>().also { map ->
  publicProperties.filter(filter).forEach { p ->
    when(val v = p.valueOf(this)) {
      "", false, 0, Int.MAX_VALUE, Int.MIN_VALUE, 0.0, Void::class.java, AccessMode.AUTO -> null
      is Enum<*> -> v.takeIf { v.name != "DEFAULT" }
      is Annotation -> v.toNonEmptyValues().takeIf { it.isNotEmpty() }
      is Array<*> -> v.map { (it as? Annotation)?.toNonEmptyValues() ?: it }.takeIf { it.isNotEmpty() }
      else -> v
    }?.let { map[p.name] = it }
  }}
