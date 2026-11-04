package com.ekta.sos

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private val Olive = Color(0xFFC9C04A)
private val OliveDark = Color(0xFF9C9533)
private val Leaf = Color(0xFF7FA63E)
private val LeafDark = Color(0xFF5C8A2E)
private val Hill = Color(0xFF6E9E3C)
private val Orange = Color(0xFFE8743C)
private val Planet = Color(0xFF3E5BD8)

// ---------- Sky ----------

fun DrawScope.drawSky() {
    val u = size.width / 400f // design unit: 400 units across the screen
    // Sparkles
    sparkle(Offset(size.width * 0.90f, size.height * 0.23f), 12 * u)
    sparkle(Offset(size.width * 0.08f, size.height * 0.17f), 8 * u)
    sparkle(Offset(size.width * 0.70f, size.height * 0.06f), 6 * u)
    sparkle(Offset(size.width * 0.22f, size.height * 0.08f), 5 * u)
    sparkle(Offset(size.width * 0.92f, size.height * 0.48f), 6 * u)
    // Striped planet with orbit lines
    planet(Offset(size.width * 0.86f, size.height * 0.075f), 26 * u, u)
}

private fun DrawScope.sparkle(c: Offset, r: Float) {
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        val len = if (i % 2 == 0) r else r * 0.55f
        drawLine(
            Ink, c, Offset(c.x + cos(a).toFloat() * len, c.y + sin(a).toFloat() * len),
            strokeWidth = r * 0.22f, cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.planet(c: Offset, r: Float, u: Float) {
    // orbit arcs
    for (i in 0..2) {
        drawArc(
            Ink, startAngle = 120f + i * 4, sweepAngle = 110f, useCenter = false,
            topLeft = Offset(c.x - r - (6 + i * 5) * u, c.y - r - (6 + i * 5) * u),
            size = Size((r + (6 + i * 5) * u) * 2, (r + (6 + i * 5) * u) * 2),
            style = Stroke(width = 1.5f * u, cap = StrokeCap.Round),
        )
    }
    drawCircle(Pink, r, c)
    val disc = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(c, r))
    }
    clipPath(disc) {
        rotate(-25f, c) {
            var y = c.y - r
            var row = 0
            while (y < c.y + r) {
                val wave = Path()
                wave.moveTo(c.x - r, y)
                var x = c.x - r
                while (x <= c.x + r) {
                    wave.lineTo(x, y + sin((x + row * 13) / (6 * u)) * 3 * u)
                    x += 2 * u
                }
                drawPath(wave, Planet, style = Stroke(width = 4.5f * u, cap = StrokeCap.Round))
                y += 11 * u
                row++
            }
        }
    }
    drawCircle(Ink, r, c, style = Stroke(width = 2.5f * u))
}

// ---------- Forest ----------

fun DrawScope.drawForest() {
    val u = size.width / 400f
    val h = size.height
    val rnd = Random(7)
    val outline = Stroke(width = 2.2f * u, cap = StrokeCap.Round)

    // Back row: tall pines
    var x = -10 * u
    while (x < size.width + 20 * u) {
        val treeH = h * (0.55f + rnd.nextFloat() * 0.35f)
        pine(Offset(x, h), 24 * u + rnd.nextFloat() * 10 * u, treeH, outline)
        x += 30 * u + rnd.nextFloat() * 22 * u
    }

    // Middle: round leafy trees
    x = 20 * u
    while (x < size.width) {
        val top = h * (0.35f + rnd.nextFloat() * 0.3f)
        bushyTree(Offset(x, h), top, 18 * u + rnd.nextFloat() * 10 * u, outline, rnd)
        x += 70 * u + rnd.nextFloat() * 40 * u
    }

    // Flowers popping up
    x = 8 * u
    while (x < size.width) {
        flower(Offset(x, h), h * (0.25f + rnd.nextFloat() * 0.25f), 7 * u, outline, u)
        x += 45 * u + rnd.nextFloat() * 50 * u
    }

    // Front hill
    val hill = Path().apply {
        moveTo(0f, h * 0.86f)
        cubicTo(size.width * 0.25f, h * 0.76f, size.width * 0.55f, h * 0.92f, size.width * 0.75f, h * 0.82f)
        cubicTo(size.width * 0.88f, h * 0.76f, size.width, h * 0.80f, size.width, h * 0.80f)
        lineTo(size.width, h)
        lineTo(0f, h)
        close()
    }
    drawPath(hill, Hill)
    drawPath(hill, Ink, style = outline)

    // Little grass ticks on the hill
    repeat(40) {
        val gx = rnd.nextFloat() * size.width
        val gy = h * (0.9f + rnd.nextFloat() * 0.08f)
        drawLine(Ink, Offset(gx, gy), Offset(gx + 2 * u, gy - 5 * u), 1.4f * u, StrokeCap.Round)
    }

    // Floating sparkles above the trees
    repeat(6) {
        drawCircle(Ink, 1.6f * u, Offset(rnd.nextFloat() * size.width, h * rnd.nextFloat() * 0.3f))
    }
}

private fun DrawScope.pine(base: Offset, halfW: Float, height: Float, outline: Stroke) {
    // trunk
    drawLine(Ink, base, Offset(base.x, base.y - height), outline.width * 1.2f, StrokeCap.Round)
    // stacked jagged tiers
    val tiers = 4
    for (i in 0 until tiers) {
        val tierTop = base.y - height + i * height * 0.2f
        val tierBottom = tierTop + height * 0.32f
        val w = halfW * (0.45f + i * 0.2f)
        val p = Path().apply {
            moveTo(base.x, tierTop)
            lineTo(base.x + w, tierBottom)
            lineTo(base.x + w * 0.5f, tierBottom - height * 0.03f)
            lineTo(base.x, tierBottom + height * 0.01f)
            lineTo(base.x - w * 0.5f, tierBottom - height * 0.03f)
            lineTo(base.x - w, tierBottom)
            close()
        }
        drawPath(p, if (i % 2 == 0) Olive else OliveDark)
        drawPath(p, Ink, style = outline)
    }
}

private fun DrawScope.bushyTree(base: Offset, topY: Float, r: Float, outline: Stroke, rnd: Random) {
    drawLine(Ink, base, Offset(base.x, topY + r), outline.width * 1.6f, StrokeCap.Round)
    val blobs = listOf(
        Offset(0f, 0f), Offset(-r * 0.9f, r * 0.5f), Offset(r * 0.9f, r * 0.5f),
        Offset(-r * 0.4f, -r * 0.6f), Offset(r * 0.5f, -r * 0.5f),
    )
    blobs.forEach { o ->
        val c = Offset(base.x + o.x, topY + r + o.y)
        val rr = r * (0.7f + rnd.nextFloat() * 0.25f)
        drawCircle(if (rnd.nextBoolean()) Leaf else LeafDark, rr, c)
        drawCircle(Ink, rr, c, style = outline)
    }
}

private fun DrawScope.flower(base: Offset, height: Float, r: Float, outline: Stroke, u: Float) {
    val head = Offset(base.x, base.y - height)
    drawLine(Ink, base, head, outline.width, StrokeCap.Round)
    // a leaf on the stem
    drawLine(Ink, Offset(base.x, base.y - height * 0.4f), Offset(base.x + 8 * u, base.y - height * 0.55f), outline.width, StrokeCap.Round)
    for (i in 0 until 6) {
        val a = Math.toRadians(i * 60.0)
        val pc = Offset(head.x + cos(a).toFloat() * r, head.y + sin(a).toFloat() * r)
        drawCircle(Orange, r * 0.7f, pc)
        drawCircle(Ink, r * 0.7f, pc, style = outline)
    }
    drawCircle(Pink, r * 0.6f, head)
    drawCircle(Ink, r * 0.6f, head, style = outline)
}
