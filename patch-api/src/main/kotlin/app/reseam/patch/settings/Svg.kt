// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.patch.settings

import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

internal object SvgReader {
    private val SHAPES = setOf("path", "circle", "ellipse", "rect", "line", "polyline", "polygon")
    private val PAINT =
        setOf("fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "fill-rule")
    private val GEOMETRY =
        mapOf(
            "svg" to setOf("xmlns", "width", "height", "viewBox", "class"),
            "g" to setOf("class"),
            "path" to setOf("d"),
            "circle" to setOf("cx", "cy", "r"),
            "ellipse" to setOf("cx", "cy", "rx", "ry"),
            "rect" to setOf("x", "y", "width", "height", "rx", "ry"),
            "line" to setOf("x1", "y1", "x2", "y2"),
            "polyline" to setOf("points"),
            "polygon" to setOf("points"),
        )

    fun read(source: String): Vector {
        val root =
            DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(InputSource(source.reader()))
                .documentElement
        require(root.tagName == "svg") {
            "an SVG document starts with <svg>, not <${root.tagName}>"
        }
        val box =
            root.getAttribute("viewBox").takeIf(String::isNotBlank)?.let(::numbers)
                ?: listOf(0f, 0f, root.number("width"), root.number("height"))
        require(box.size == 4 && box[2] > 0 && box[3] > 0) {
            "viewBox needs a positive width and height"
        }
        val paths = mutableListOf<VectorPath>()
        collect(root, Style.INITIAL, PathNormalizer(-box[0], -box[1]), paths)
        require(paths.isNotEmpty()) { "the SVG draws nothing" }
        return Vector(box[2], box[3], paths)
    }

    private fun collect(
        element: Element,
        inherited: Style,
        normalizer: PathNormalizer,
        paths: MutableList<VectorPath>,
    ) {
        val name = element.tagName
        val geometry = GEOMETRY[name] ?: throw IllegalArgumentException("unsupported SVG <$name>")
        val attributes = element.attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index).nodeName
            require(attribute in geometry || attribute in PAINT || attribute.startsWith("xmlns:")) {
                "unsupported attribute $attribute on SVG <$name>"
            }
        }
        val style = inherited.with(element)
        if (name in SHAPES) {
            val data = normalizer.normalize(outline(element))
            val fill = style.fill?.let { Fill(it, style.evenOdd) }
            val stroke = style.stroke?.let { Stroke(it, style.strokeWidth, style.cap, style.join) }
            if (fill != null || stroke != null) paths += VectorPath(data, fill, stroke)
        }
        val children = element.childNodes
        for (index in 0 until children.length) {
            (children.item(index) as? Element)?.let { collect(it, style, normalizer, paths) }
        }
    }

    private fun outline(element: Element): String =
        when (element.tagName) {
            "path" -> element.getAttribute("d")
            "circle" -> ellipse(element.number("cx"), element.number("cy"), element.number("r"))
            "ellipse" ->
                ellipse(
                    element.number("cx"),
                    element.number("cy"),
                    element.number("rx"),
                    element.number("ry"),
                )
            "rect" -> rect(element)
            "line" ->
                "M ${element.number("x1")} ${element.number("y1")} " +
                    "L ${element.number("x2")} ${element.number("y2")}"
            "polyline" -> points(element)
            "polygon" -> points(element) + " Z"
            else -> ""
        }

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float = rx) =
        "M ${cx - rx} $cy A $rx $ry 0 1 0 ${cx + rx} $cy A $rx $ry 0 1 0 ${cx - rx} $cy Z"

    private fun rect(element: Element): String {
        val x = element.number("x")
        val y = element.number("y")
        val width = element.number("width")
        val height = element.number("height")
        val rxAttribute = element.getAttribute("rx").takeIf(String::isNotBlank)?.toFloat()
        val ryAttribute = element.getAttribute("ry").takeIf(String::isNotBlank)?.toFloat()
        val rx = (rxAttribute ?: ryAttribute ?: 0f).coerceAtMost(width / 2)
        val ry = (ryAttribute ?: rxAttribute ?: 0f).coerceAtMost(height / 2)
        if (rx == 0f || ry == 0f) return "M $x $y H ${x + width} V ${y + height} H $x Z"
        return "M ${x + rx} $y H ${x + width - rx} A $rx $ry 0 0 1 ${x + width} ${y + ry} " +
            "V ${y + height - ry} A $rx $ry 0 0 1 ${x + width - rx} ${y + height} " +
            "H ${x + rx} A $rx $ry 0 0 1 $x ${y + height - ry} V ${y + ry} " +
            "A $rx $ry 0 0 1 ${x + rx} $y Z"
    }

    private fun points(element: Element): String {
        val values = numbers(element.getAttribute("points"))
        require(values.size >= 4 && values.size % 2 == 0) {
            "<${element.tagName}> needs at least two x,y points"
        }
        return values.chunked(2).withIndex().joinToString(" ") { (index, point) ->
            "${if (index == 0) "M" else "L"} ${point[0]} ${point[1]}"
        }
    }

    private fun Element.number(name: String): Float {
        val value = getAttribute(name)
        if (value.isBlank()) return 0f
        return value.removeSuffix("px").toFloatOrNull()
            ?: throw IllegalArgumentException("$name=\"$value\" on SVG <$tagName> is not a number")
    }

    private fun numbers(value: String): List<Float> =
        value.trim().split(Regex("[\\s,]+")).filter(String::isNotEmpty).map {
            it.toFloatOrNull() ?: throw IllegalArgumentException("\"$it\" is not a number")
        }

    private data class Style(
        val fill: Paint?,
        val stroke: Paint?,
        val strokeWidth: Float,
        val cap: Cap,
        val join: Join,
        val evenOdd: Boolean,
    ) {
        fun with(element: Element): Style {
            fun attribute(name: String) = element.getAttribute(name).takeIf(String::isNotBlank)
            fun paint(name: String, inherited: Paint?): Paint? {
                val value = attribute(name) ?: return inherited
                return paint(value)
            }
            return Style(
                fill = paint("fill", fill),
                stroke = paint("stroke", stroke),
                strokeWidth =
                    attribute("stroke-width")?.let {
                        it.toFloatOrNull()
                            ?: throw IllegalArgumentException(
                                "stroke-width=\"$it\" is not a number"
                            )
                    } ?: strokeWidth,
                cap = attribute("stroke-linecap")?.let { enumOf<Cap>("stroke-linecap", it) } ?: cap,
                join =
                    attribute("stroke-linejoin")?.let { enumOf<Join>("stroke-linejoin", it) }
                        ?: join,
                evenOdd =
                    attribute("fill-rule")?.let {
                        when (it) {
                            "evenodd" -> true
                            "nonzero" -> false
                            else -> throw IllegalArgumentException("unsupported fill-rule $it")
                        }
                    } ?: evenOdd,
            )
        }

        companion object {
            /** SVG's initial values: a black fill and no stroke. */
            val INITIAL =
                Style(
                    fill = Paint.Solid(Color.hex("#000000")),
                    stroke = null,
                    strokeWidth = 1f,
                    cap = Cap.BUTT,
                    join = Join.MITER,
                    evenOdd = false,
                )

            private fun paint(value: String): Paint? =
                when (value) {
                    "none" -> null
                    "currentColor" -> Paint.Current
                    else -> Paint.Solid(Color.hex(value))
                }

            private inline fun <reified T : Enum<T>> enumOf(attribute: String, value: String): T =
                enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
                    ?: throw IllegalArgumentException("unsupported $attribute $value")
        }
    }
}

/**
 * Rewrites SVG path data as absolute move, line, cubic and close commands, shifted by an offset, so
 * the app runtime needs no SVG parser. Arcs and quadratic curves become cubics.
 */
internal class PathNormalizer(private val dx: Float, private val dy: Float) {
    fun normalize(data: String): String {
        val tokens = PathTokens(data)
        val out = StringBuilder()
        var x = 0.0
        var y = 0.0
        var startX = 0.0
        var startY = 0.0
        // The previous segment's last control point, for the smooth S and T commands.
        var controlX = 0.0
        var controlY = 0.0
        var previous = ' '
        var command = ' '

        fun emit(letter: Char, vararg values: Double) {
            if (out.isNotEmpty()) out.append(' ')
            out.append(letter)
            values.forEachIndexed { index, value ->
                out.append(' ')
                out.append(format(value + if (index % 2 == 0) dx else dy))
            }
        }

        fun cubic(x1: Double, y1: Double, x2: Double, y2: Double, endX: Double, endY: Double) {
            emit('C', x1, y1, x2, y2, endX, endY)
            controlX = x2
            controlY = y2
            x = endX
            y = endY
        }

        while (tokens.hasMore()) {
            command = tokens.command() ?: command
            require(command != ' ') { "path data must start with a command" }
            val relative = command.isLowerCase()
            val baseX = if (relative) x else 0.0
            val baseY = if (relative) y else 0.0
            when (command.uppercaseChar()) {
                'M' -> {
                    x = baseX + tokens.number()
                    y = baseY + tokens.number()
                    startX = x
                    startY = y
                    emit('M', x, y)
                    // Further coordinate pairs after a move are lines.
                    command = if (relative) 'l' else 'L'
                }
                'L' -> {
                    x = baseX + tokens.number()
                    y = baseY + tokens.number()
                    emit('L', x, y)
                }
                'H' -> {
                    x = baseX + tokens.number()
                    emit('L', x, y)
                }
                'V' -> {
                    y = baseY + tokens.number()
                    emit('L', x, y)
                }
                'C' ->
                    cubic(
                        baseX + tokens.number(),
                        baseY + tokens.number(),
                        baseX + tokens.number(),
                        baseY + tokens.number(),
                        baseX + tokens.number(),
                        baseY + tokens.number(),
                    )
                'S' -> {
                    val smooth = previous.uppercaseChar() in "CS"
                    cubic(
                        if (smooth) 2 * x - controlX else x,
                        if (smooth) 2 * y - controlY else y,
                        baseX + tokens.number(),
                        baseY + tokens.number(),
                        baseX + tokens.number(),
                        baseY + tokens.number(),
                    )
                }
                'Q' -> {
                    val qx = baseX + tokens.number()
                    val qy = baseY + tokens.number()
                    quadratic(
                        qx,
                        qy,
                        baseX + tokens.number(),
                        baseY + tokens.number(),
                        x,
                        y,
                        ::cubic,
                    )
                    controlX = qx
                    controlY = qy
                }
                'T' -> {
                    val smooth = previous.uppercaseChar() in "QT"
                    val qx = if (smooth) 2 * x - controlX else x
                    val qy = if (smooth) 2 * y - controlY else y
                    quadratic(
                        qx,
                        qy,
                        baseX + tokens.number(),
                        baseY + tokens.number(),
                        x,
                        y,
                        ::cubic,
                    )
                    controlX = qx
                    controlY = qy
                }
                'A' -> {
                    val rx = tokens.number()
                    val ry = tokens.number()
                    val rotation = tokens.number()
                    val large = tokens.flag()
                    val sweep = tokens.flag()
                    arc(
                        x,
                        y,
                        rx,
                        ry,
                        rotation,
                        large,
                        sweep,
                        baseX + tokens.number(),
                        baseY + tokens.number(),
                        ::cubic,
                    )
                }
                'Z' -> {
                    emit('Z')
                    x = startX
                    y = startY
                }
                else -> throw IllegalArgumentException("unknown path command $command")
            }
            previous = command
        }
        return out.toString()
    }

    private fun quadratic(
        qx: Double,
        qy: Double,
        endX: Double,
        endY: Double,
        fromX: Double,
        fromY: Double,
        cubic: (Double, Double, Double, Double, Double, Double) -> Unit,
    ) =
        cubic(
            fromX + 2.0 / 3 * (qx - fromX),
            fromY + 2.0 / 3 * (qy - fromY),
            endX + 2.0 / 3 * (qx - endX),
            endY + 2.0 / 3 * (qy - endY),
            endX,
            endY,
        )

    /** Approximates an elliptical arc with cubics, following SVG's arc implementation notes. */
    private fun arc(
        fromX: Double,
        fromY: Double,
        radiusX: Double,
        radiusY: Double,
        rotationDegrees: Double,
        large: Boolean,
        sweep: Boolean,
        endX: Double,
        endY: Double,
        cubic: (Double, Double, Double, Double, Double, Double) -> Unit,
    ) {
        if (fromX == endX && fromY == endY) return
        var rx = kotlin.math.abs(radiusX)
        var ry = kotlin.math.abs(radiusY)
        if (rx == 0.0 || ry == 0.0) {
            cubic(fromX, fromY, endX, endY, endX, endY)
            return
        }
        val phi = Math.toRadians(rotationDegrees)
        val cos = kotlin.math.cos(phi)
        val sin = kotlin.math.sin(phi)
        val halfX = (fromX - endX) / 2
        val halfY = (fromY - endY) / 2
        val x1 = cos * halfX + sin * halfY
        val y1 = -sin * halfX + cos * halfY
        val lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
        if (lambda > 1) {
            rx *= kotlin.math.sqrt(lambda)
            ry *= kotlin.math.sqrt(lambda)
        }
        val numerator = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
        val denominator = rx * rx * y1 * y1 + ry * ry * x1 * x1
        val root =
            kotlin.math.sqrt((numerator / denominator).coerceAtLeast(0.0)) *
                if (large == sweep) -1 else 1
        val cx1 = root * rx * y1 / ry
        val cy1 = -root * ry * x1 / rx
        val cx = cos * cx1 - sin * cy1 + (fromX + endX) / 2
        val cy = sin * cx1 + cos * cy1 + (fromY + endY) / 2
        val start = angle(1.0, 0.0, (x1 - cx1) / rx, (y1 - cy1) / ry)
        var delta = angle((x1 - cx1) / rx, (y1 - cy1) / ry, (-x1 - cx1) / rx, (-y1 - cy1) / ry)
        if (!sweep && delta > 0) delta -= 2 * Math.PI
        if (sweep && delta < 0) delta += 2 * Math.PI
        val segments = kotlin.math.ceil(kotlin.math.abs(delta) / (Math.PI / 2)).toInt()
        val step = delta / segments
        val k = 4.0 / 3 * kotlin.math.tan(step / 4)
        fun point(
            theta: Double,
            scale: Double = 1.0,
            tangent: Boolean = false,
        ): Pair<Double, Double> {
            val ex = if (tangent) -rx * kotlin.math.sin(theta) else rx * kotlin.math.cos(theta)
            val ey = if (tangent) ry * kotlin.math.cos(theta) else ry * kotlin.math.sin(theta)
            return Pair(cos * ex * scale - sin * ey * scale, sin * ex * scale + cos * ey * scale)
        }
        var theta = start
        repeat(segments) {
            val next = theta + step
            val (sx, sy) = point(theta)
            val (ex, ey) = point(next)
            val (t1x, t1y) = point(theta, k, tangent = true)
            val (t2x, t2y) = point(next, k, tangent = true)
            cubic(
                cx + sx + t1x,
                cy + sy + t1y,
                cx + ex - t2x,
                cy + ey - t2y,
                cx + ex,
                cy + ey,
            )
            theta = next
        }
    }

    private fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double =
        kotlin.math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)

    private fun format(value: Double): String {
        val rounded = Math.round(value * 1000) / 1000.0
        return if (rounded == kotlin.math.floor(rounded)) rounded.toLong().toString()
        else rounded.toString()
    }
}

private class PathTokens(private val data: String) {
    private var index = 0

    fun hasMore(): Boolean {
        skipSeparators()
        return index < data.length
    }

    /** The next command letter, or null when the next token is a number that repeats one. */
    fun command(): Char? {
        skipSeparators()
        val char = data[index]
        if (!char.isLetter()) return null
        index++
        return char
    }

    fun number(): Double {
        skipSeparators()
        val start = index
        if (index < data.length && data[index] in "+-") index++
        var seenDot = false
        var seenExponent = false
        while (index < data.length) {
            val char = data[index]
            when {
                char.isDigit() -> index++
                char == '.' && !seenDot && !seenExponent -> {
                    seenDot = true
                    index++
                }
                char in "eE" && !seenExponent && index > start -> {
                    seenExponent = true
                    index++
                    if (index < data.length && data[index] in "+-") index++
                }
                else -> break
            }
        }
        return data.substring(start, index).toDoubleOrNull()
            ?: throw IllegalArgumentException("expected a number at $start in path data \"$data\"")
    }

    fun flag(): Boolean {
        skipSeparators()
        return when (data.getOrNull(index++)) {
            '0' -> false
            '1' -> true
            else ->
                throw IllegalArgumentException("expected an arc flag at ${index - 1} in \"$data\"")
        }
    }

    private fun skipSeparators() {
        while (index < data.length && (data[index].isWhitespace() || data[index] == ',')) index++
    }
}
