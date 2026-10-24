package me.ailinux.pocket.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Bounded JSON Schema *subset* for local MCP tool execution.
 *
 * The MCP spec uses JSON Schema for inputSchema. We never silently ignore an
 * unfamiliar assertion keyword or send a partially validated request:
 * complex schemas are rejected until supported. For a tiny GGUF, a small
 * predictable tool schema is preferable to risky permissive execution.
 */
object McpToolSchema {
    private const val MAX_DEPTH = 5
    private const val MAX_PROPERTIES = 32
    private const val MAX_ARRAY = 64
    private val allowed = setOf(
        "type", "properties", "required", "additionalProperties",
        "items", "enum", "const", "minLength", "maxLength",
        "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
        "minItems", "maxItems", "minProperties", "maxProperties",
        "description", "title", "default", "examples",
        "deprecated", "readOnly", "writeOnly", "\$schema", "\$id",
        "x-mcp-header"
    )
    private val knownTypes = setOf("string", "integer", "number", "boolean", "array", "object")

    /** Fail closed if the server publishes assertions we cannot enforce. */
    fun supported(schema: JSONObject): String? = checkSchema(schema, 0)

    private fun checkSchema(schema: JSONObject, depth: Int): String? {
        if (depth > MAX_DEPTH) return "MCP-Schema ist zu tief verschachtelt"
        val keys = schema.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in allowed) return "Nicht unterstützte MCP-JSON-Schema-Regel: $key"
        }
        val type = schema.optString("type")
        if (type.isNotEmpty() && type !in knownTypes)
            return "Unbekannter MCP-Schema-Typ: $type"
        if (schema.opt("type") is JSONArray)
            return "Mehrdeutige JSON-Schema-Typen nicht unterstützt"
        for (key in listOf("required", "enum", "examples")) {
            if (schema.has(key) && schema.opt(key) !is JSONArray)
                return "MCP-Schema $key ist kein Array"
        }
        for (key in listOf("minLength", "maxLength", "minItems", "maxItems",
            "minProperties", "maxProperties")) {
            if (schema.has(key) && (schema.opt(key) !is Number ||
                schema.optInt(key, -1) < 0)) return "Ungültige MCP-Schema-Grenze: $key"
        }
        for (key in listOf("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum")) {
            if (schema.has(key) && schema.opt(key) !is Number)
                return "Ungültige numerische MCP-Schema-Grenze: $key"
        }
        for (key in listOf("additionalProperties")) {
            if (schema.has(key) && schema.opt(key) !is Boolean)
                return "Komplexe zusätzliche Eigenschaften nicht unterstützt"
        }
        if (schema.has("properties")) {
            val properties = schema.optJSONObject("properties")
                ?: return "MCP-Schema properties ist kein Objekt"
            if (properties.length() > MAX_PROPERTIES)
                return "MCP-Schema enthält zu viele Parameter"
            val names = properties.keys()
            while (names.hasNext()) {
                val name = names.next()
                val child = properties.optJSONObject(name)
                    ?: return "Ungültiges MCP-Feldschema: $name"
                checkSchema(child, depth + 1)?.let { return it }
            }
        }
        if (schema.has("items")) {
            val child = schema.optJSONObject("items")
                ?: return "MCP-Array ohne einzelnes definiertes Feldschema"
            checkSchema(child, depth + 1)?.let { return it }
        }
        if (schema.has("required")) {
            val mandatory = schema.optJSONArray("required")!!
            val seen = hashSetOf<String>()
            for (i in 0 until mandatory.length()) {
                val entry = mandatory.opt(i) as? String
                    ?: return "Ungültiger MCP-Pflichtparameter"
                if (!seen.add(entry)) return "Doppelter MCP-Pflichtparameter"
                if (schema.optJSONObject("properties")?.has(entry) != true)
                    return "MCP-Pflichtparameter ohne Feldschema: $entry"
            }
        }
        return null
    }

    fun validate(schema: JSONObject, arguments: JSONObject): String? {
        supported(schema)?.let { return it }
        if (schema.optString("type") !in setOf("", "object"))
            return "MCP-Eingabeschema muss ein JSON-Objekt beschreiben"
        return checkValue(schema, arguments, 0, "Argumente")
    }

    private fun checkValue(schema: JSONObject, value: Any, depth: Int, path: String): String? {
        if (depth > MAX_DEPTH) return "Parameter zu tief verschachtelt: $path"
        if (value === JSONObject.NULL) return "Nullwert nicht unterstützt: $path"
        val kind = schema.optString("type")
        val typeValid = when (kind) {
            "string" -> value is String
            "integer" -> value is Number && value.toString().matches(Regex("-?[0-9]+"))
            "number" -> value is Number
            "boolean" -> value is Boolean
            "object" -> value is JSONObject
            "array" -> value is JSONArray
            else -> true
        }
        if (!typeValid) return "Falscher MCP-Datentyp für $path"
        val enumeration = schema.optJSONArray("enum")
        if (enumeration != null &&
            (0 until enumeration.length()).none { equivalent(enumeration.opt(it), value) }
        ) return "Wert nicht in MCP-enum für $path"
        if (schema.has("const") && !equivalent(schema.opt("const"), value))
            return "MCP-Konstante verletzt: $path"
        when (value) {
            is String -> {
                if (schema.has("minLength") && value.codePointCount(0, value.length) <
                    schema.optInt("minLength")) return "$path zu kurz"
                if (schema.has("maxLength") && value.codePointCount(0, value.length) >
                    schema.optInt("maxLength")) return "$path zu lang"
            }
            is Number -> {
                val number = runCatching { BigDecimal(value.toString()) }.getOrNull()
                    ?: return "Nicht endliche MCP-Zahl: $path"
                for (rule in listOf("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum")) {
                    if (!schema.has(rule)) continue
                    val bound = BigDecimal(schema.get(rule).toString())
                    val compare = number.compareTo(bound)
                    if (rule == "minimum" && compare < 0 ||
                        rule == "maximum" && compare > 0 ||
                        rule == "exclusiveMinimum" && compare <= 0 ||
                        rule == "exclusiveMaximum" && compare >= 0
                    ) return "MCP-Zahl außerhalb $rule: $path"
                }
            }
            is JSONObject -> {
                if (value.length() > MAX_PROPERTIES) return "Zu viele MCP-Parameter"
                if (schema.has("minProperties") && value.length() < schema.optInt("minProperties"))
                    return "Zu wenige MCP-Felder: $path"
                if (schema.has("maxProperties") && value.length() > schema.optInt("maxProperties"))
                    return "Zu viele MCP-Felder: $path"
                val required = schema.optJSONArray("required")
                if (required != null) for (i in 0 until required.length()) {
                    val name = required.getString(i)
                    if (!value.has(name) || value.isNull(name)) return "Pflichtparameter fehlt: $name"
                }
                val properties = schema.optJSONObject("properties")
                val keys = value.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    // Least privilege: an MCP tool must never receive model
                    // invented attributes, even with additionalProperties=true.
                    val child = properties?.optJSONObject(name)
                        ?: return "Unbekannter MCP-Parameter: $name"
                    checkValue(child, value.get(name), depth + 1, "$path.$name")
                        ?.let { return it }
                }
            }
            is JSONArray -> {
                if (value.length() > MAX_ARRAY) return "MCP-Array zu groß: $path"
                if (schema.has("minItems") && value.length() < schema.optInt("minItems"))
                    return "MCP-Array zu kurz: $path"
                if (schema.has("maxItems") && value.length() > schema.optInt("maxItems"))
                    return "MCP-Array zu lang: $path"
                val items = schema.optJSONObject("items")
                if (items == null && value.length() > 0)
                    return "MCP-Array ohne definiertes Feldschema: $path"
                if (items != null) for (i in 0 until value.length()) {
                    checkValue(items, value.get(i), depth + 1, "$path[$i]")
                        ?.let { return it }
                }
            }
        }
        return null
    }

    /** Structural equality for schema TOCTOU validation and enum objects. */
    fun equivalent(first: Any?, second: Any?): Boolean =
        canonical(first) == canonical(second)

    fun canonical(value: Any?): String = when (value) {
        is JSONObject -> {
            val names = value.keys().asSequence().sorted().toList()
            names.joinToString(prefix = "{", postfix = "}") { name ->
                JSONObject.quote(name) + ":" + canonical(value.get(name))
            }
        }
        is JSONArray -> (0 until value.length()).joinToString(
            prefix = "[", postfix = "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Number -> runCatching { BigDecimal(value.toString()).stripTrailingZeros().toPlainString() }
            .getOrElse { value.toString() }
        is Boolean -> value.toString()
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }
}
