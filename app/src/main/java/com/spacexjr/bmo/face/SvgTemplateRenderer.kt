package com.spacexjr.bmo.face

import android.content.res.AssetManager
import java.util.Locale

/**
 * Renders the small, deliberately limited Go-template subset used by the
 * original BMO face SVGs. The artwork itself is never modified: we only
 * substitute the parameters the original animation code already used
 * (.m, .x, .t).
 */
class SvgTemplateRenderer(private val assets: AssetManager) {

    data class Value(val number: Double? = null, val text: String? = null) {
        fun truthy(): Boolean = number?.let { it != 0.0 } ?: text?.isNotEmpty() == true
        fun asDouble(): Double = number ?: text?.toDoubleOrNull() ?: 0.0
        fun asText(): String = text ?: formatNumber(number ?: 0.0)
    }

    private sealed interface Node {
        data class Text(val value: String) : Node
        data class Output(val expression: String) : Node
        data class Assign(val name: String, val expression: String, val define: Boolean) : Node
        data class If(val branches: List<Branch>) : Node
    }

    private data class Branch(val condition: String?, val body: List<Node>)

    private data class Token(val kind: Kind, val text: String) {
        enum class Kind { TEXT, ACTION }
    }

    fun raw(assetName: String): String {
        val name = if (assetName.endsWith(".svg")) assetName else "$assetName.svg"
        return assets.open("faces/$name").bufferedReader().use { it.readText() }
    }

    fun render(assetName: String, params: Map<String, Double> = emptyMap()): String =
        renderSource(raw(assetName), params)

    fun renderSource(source: String, params: Map<String, Double> = emptyMap()): String {
        if (!source.contains("{{")) return source
        val tokens = tokenize(source)
        val variables = HashMap<String, Value>()
        params.forEach { (key, value) -> variables["\$$key"] = Value(number = value) }
        val (nodes, _, _) = parse(tokens, 0, emptySet())
        return renderNodes(nodes, variables).trim()
    }

    fun isTemplate(assetName: String): Boolean = raw(assetName).contains("{{")

    private fun tokenize(source: String): List<Token> {
        val out = ArrayList<Token>()
        var index = 0
        while (index < source.length) {
            val start = source.indexOf("{{", index)
            if (start < 0) {
                out += Token(Token.Kind.TEXT, source.substring(index))
                break
            }
            if (start > index) out += Token(Token.Kind.TEXT, source.substring(index, start))
            val end = source.indexOf("}}", start + 2)
            if (end < 0) {
                out += Token(Token.Kind.TEXT, source.substring(start))
                break
            }
            out += Token(Token.Kind.ACTION, source.substring(start + 2, end).trim())
            index = end + 2
        }
        return out
    }

    private fun parse(
        tokens: List<Token>,
        start: Int,
        stops: Set<String>,
    ): Triple<List<Node>, Int, String?> {
        val nodes = ArrayList<Node>()
        var index = start
        while (index < tokens.size) {
            val token = tokens[index]
            if (token.kind == Token.Kind.TEXT) {
                nodes += Node.Text(token.text)
                index++
                continue
            }
            val action = token.text
            val keyword = action.substringBefore(' ')
            if (keyword in stops) {
                return Triple(nodes, index, action)
            }
            when {
                action.startsWith("if ") -> {
                    val branches = ArrayList<Branch>()
                    var condition: String? = action.removePrefix("if ").trim()
                    var cursor = index + 1
                    while (true) {
                        val (body, next, stop) = parse(tokens, cursor, setOf("else", "else if", "end"))
                        branches += Branch(condition, body)
                        if (stop == null) {
                            index = next
                            break
                        }
                        cursor = next + 1
                        when {
                            stop == "end" -> {
                                index = cursor
                                break
                            }
                            stop.startsWith("else if ") -> {
                                condition = stop.removePrefix("else if ").trim()
                            }
                            stop == "else" -> {
                                condition = null
                                val (elseBody, afterElse, endAction) = parse(tokens, cursor, setOf("end"))
                                branches += Branch(null, elseBody)
                                index = afterElse + if (endAction != null) 1 else 0
                                break
                            }
                        }
                    }
                    nodes += Node.If(branches)
                }
                action.startsWith("$") && (action.contains(":=") || Regex("^\\$\\w+\\s*=").containsMatchIn(action)) -> {
                    val define = action.contains(":=")
                    val name = action.substringBefore(if (define) ":=" else "=").trim().removePrefix("$")
                    val expression = if (define) {
                        action.substringAfter(":=").trim()
                    } else {
                        action.substringAfter("=").trim()
                    }
                    nodes += Node.Assign(name, expression, define)
                    index++
                }
                action.startsWith("template ") -> {
                    // The only template used is talkmouth, which is outside the
                    // resting frames this app displays.
                    nodes += Node.Output("0")
                    index++
                }
                else -> {
                    nodes += Node.Output(action)
                    index++
                }
            }
        }
        return Triple(nodes, index, null)
    }

    private fun renderNodes(nodes: List<Node>, variables: MutableMap<String, Value>): String {
        val out = StringBuilder()
        for (node in nodes) {
            when (node) {
                is Node.Text -> out.append(node.value)
                is Node.Output -> out.append(eval(node.expression, variables).asText())
                is Node.Assign -> {
                    val value = eval(node.expression, variables)
                    variables["\$${node.name}"] = value
                    variables[node.name] = value
                }
                is Node.If -> {
                    val branch = node.branches.firstOrNull {
                        it.condition == null || eval(it.condition, variables).truthy()
                    }
                    if (branch != null) out.append(renderNodes(branch.body, variables))
                }
            }
        }
        return out.toString()
    }

    private class Cursor(var index: Int = 0)

    private fun eval(expression: String, variables: MutableMap<String, Value>): Value {
        val tokens = expressionTokens(expression)
        if (tokens.isEmpty()) return Value(number = 0.0)
        return parseExpression(tokens, Cursor(), variables)
    }

    private fun parseExpression(
        tokens: List<String>,
        cursor: Cursor,
        variables: MutableMap<String, Value>,
    ): Value {
        if (cursor.index >= tokens.size) return Value(number = 0.0)
        val token = tokens[cursor.index]
        if (token == "(") {
            cursor.index++
            val value = parseExpression(tokens, cursor, variables)
            if (cursor.index < tokens.size && tokens[cursor.index] == ")") cursor.index++
            return value
        }
        val arity = ARITY[token]
        if (arity != null) {
            cursor.index++
            val args = ArrayList<Value>(arity)
            repeat(arity) { args += parseExpression(tokens, cursor, variables) }
            return call(token, args)
        }
        cursor.index++
        if (token.startsWith("\"")) return Value(text = unquote(token))
        if (token.startsWith("$") || token.startsWith(".")) {
            return variables[token] ?: Value(number = 0.0)
        }
        return Value(number = token.toDoubleOrNull() ?: 0.0)
    }

    private fun call(name: String, args: List<Value>): Value {
        fun a(i: Int) = args.getOrNull(i) ?: Value(number = 0.0)
        return when (name) {
            "add" -> Value(number = a(0).asDouble() + a(1).asDouble())
            "sub" -> Value(number = a(0).asDouble() - a(1).asDouble())
            "mul" -> Value(number = a(0).asDouble() * a(1).asDouble())
            "mod" -> Value(number = a(0).asDouble().mod(a(1).asDouble()))
            "sin" -> Value(number = kotlin.math.sin(a(0).asDouble()))
            "or" -> if (a(0).truthy()) a(0) else a(1)
            "eq" -> Value(number = if (a(0).asText() == a(1).asText()) 1.0 else 0.0)
            "lt" -> Value(number = if (a(0).asDouble() < a(1).asDouble()) 1.0 else 0.0)
            "gt" -> Value(number = if (a(0).asDouble() > a(1).asDouble()) 1.0 else 0.0)
            "printf" -> {
                val fmt = a(0).asText()
                val value = a(1)
                val formatted = if (value.number != null) {
                    String.format(Locale.US, fmt, value.number)
                } else {
                    String.format(Locale.US, fmt, value.text ?: "")
                }
                Value(text = formatted)
            }
            else -> Value(number = 0.0)
        }
    }

    private fun expressionTokens(expression: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < expression.length) {
            val c = expression[i]
            when {
                c.isWhitespace() -> i++
                c == '(' || c == ')' -> {
                    out += c.toString(); i++
                }
                c == '"' -> {
                    val start = i
                    i++
                    while (i < expression.length) {
                        if (expression[i] == '\\') i += 2
                        else if (expression[i] == '"') { i++; break }
                        else i++
                    }
                    out += expression.substring(start, i)
                }
                else -> {
                    val start = i
                    while (i < expression.length && !expression[i].isWhitespace() &&
                        expression[i] != '(' && expression[i] != ')'
                    ) i++
                    out += expression.substring(start, i)
                }
            }
        }
        return out
    }

    private fun unquote(value: String): String {
        if (value.length < 2) return value
        return value.substring(1, value.length - 1)
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\\\", "\\")
    }

    companion object {
        private val ARITY = mapOf(
            "add" to 2, "sub" to 2, "mul" to 2, "mod" to 2,
            "sin" to 1, "or" to 2, "eq" to 2, "lt" to 2, "gt" to 2,
            "printf" to 2,
        )

        fun formatNumber(number: Double): String {
            if (number.isNaN()) return "0"
            if (number.isInfinite()) return "0"
            val rounded = String.format(Locale.US, "%.4f", number).trimEnd('0').trimEnd('.')
            return if (rounded == "-0") "0" else rounded
        }
    }
}
