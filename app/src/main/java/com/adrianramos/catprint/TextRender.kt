package com.adrianramos.catprint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan

/**
 * Draws plain text or Markdown (Obsidian flavour) straight in printer dots, black on white.
 *
 * Markdown: headings, bold/italic/strikethrough/`code`/==highlight==, links, bullet, numbered
 * and task lists, quotes and callouts (> [!note]), fenced code, tables, rules, front matter,
 * %% comments %%, [[wikilinks]] and $math$ / $$math$$ (common LaTeX turned into Unicode).
 */
object TextRender {
    private const val PAD = 8
    private const val MAX_HEIGHT = 20000 // ~2.5 m of paper

    fun plain(text: String, size: Float): Bitmap {
        val layout = layout(text, paint(size), PRINT_WIDTH - 2 * PAD)
        return toBitmap(object : Piece {
            override val height = layout.height
            override fun draw(c: Canvas, x: Float, y: Float) = drawLayout(c, layout, x, y)
        })
    }

    fun markdown(text: String, size: Float): Bitmap =
        toBitmap(Md(size).stack(Md.parse(preprocess(text)), PRINT_WIDTH - 2 * PAD))

    private fun toBitmap(p: Piece): Bitmap {
        val h = p.height + 2 * PAD
        if (h > MAX_HEIGHT) throw IllegalArgumentException("El texto es demasiado largo para imprimirlo de una vez.")
        val bmp = Bitmap.createBitmap(PRINT_WIDTH, maxOf(h, 1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        p.draw(c, PAD.toFloat(), PAD.toFloat())
        return bmp
    }

    // ---- primitives ---------------------------------------------------------------------------

    private interface Piece {
        val height: Int
        fun draw(c: Canvas, x: Float, y: Float)
    }

    private fun paint(size: Float, bold: Boolean = false, mono: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = size
        typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private val line = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
    private val stroke = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f }

    private fun layout(text: CharSequence, p: TextPaint, width: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) =
        StaticLayout.Builder.obtain(text, 0, text.length, p, maxOf(width, 1))
            .setAlignment(align)
            .setLineSpacing(2f, 1f)
            .setIncludePad(false)
            .build()

    private fun drawLayout(c: Canvas, l: Layout, x: Float, y: Float) {
        c.save(); c.translate(x, y); l.draw(c); c.restore()
    }

    private fun textPiece(text: CharSequence, p: TextPaint, width: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): Piece {
        val l = layout(text, p, width, align)
        return object : Piece {
            override val height = l.height
            override fun draw(c: Canvas, x: Float, y: Float) = drawLayout(c, l, x, y)
        }
    }

    // ---- Obsidian syntax ----------------------------------------------------------------------

    private fun preprocess(src: String): String {
        var t = src.replace("\r\n", "\n")
        t = Regex("""\A---\n.*?\n---[ \t]*(\n|\z)""", RegexOption.DOT_MATCHES_ALL).replace(t, "")
        t = Regex("""%%.*?%%""", RegexOption.DOT_MATCHES_ALL).replace(t, "")
        t = Regex("""!\[\[([^\]]+)\]\]""").replace(t) { "*[imagen: ${it.groupValues[1].substringBefore('|')}]*" }
        t = Regex("""\[\[([^\]|]+)\|([^\]]+)\]\]""").replace(t) { it.groupValues[2] }
        t = Regex("""\[\[([^\]]+)\]\]""").replace(t) { it.groupValues[1].replace('#', ' ') }
        return t
    }

    // ---- inline -------------------------------------------------------------------------------

    private val INLINE = Regex(
        """`([^`]+)`""" +
            """|\$([^$\s](?:[^$\n]*?[^$\s])?)\$""" +
            """|\*\*(.+?)\*\*|__(.+?)__""" +
            """|~~(.+?)~~""" +
            """|==(.+?)==""" +
            """|\*([^*\s](?:[^*]*?[^*\s])?)\*|(?<!\w)_([^_\s](?:[^_]*?[^_\s])?)_(?!\w)""" +
            """|(!?)\[([^\]]*)\]\(([^)]*)\)"""
    )

    private fun inline(s: String, sb: SpannableStringBuilder = SpannableStringBuilder()): SpannableStringBuilder {
        var i = 0
        for (m in INLINE.findAll(s)) {
            sb.append(unescape(s.substring(i, m.range.first)))
            val start = sb.length
            val g = m.groups
            fun span(vararg spans: Any) = spans.forEach { sb.setSpan(it, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            when {
                g[1] != null -> { sb.append(g[1]!!.value); span(TypefaceSpan("monospace")) }
                g[2] != null -> Math.inline(g[2]!!.value, sb)
                g[3] != null || g[4] != null -> { inline((g[3] ?: g[4])!!.value, sb); span(StyleSpan(Typeface.BOLD)) }
                g[5] != null -> { inline(g[5]!!.value, sb); span(StrikethroughSpan()) }
                g[6] != null -> { inline(g[6]!!.value, sb); span(BackgroundColorSpan(Color.BLACK), ForegroundColorSpan(Color.WHITE)) }
                g[7] != null || g[8] != null -> { inline((g[7] ?: g[8])!!.value, sb); span(StyleSpan(Typeface.ITALIC)) }
                g[9]!!.value == "!" -> { sb.append("[imagen]"); span(StyleSpan(Typeface.ITALIC)) }
                else -> { inline(g[10]!!.value, sb); span(UnderlineSpan()) }
            }
            i = m.range.last + 1
        }
        sb.append(unescape(s.substring(i)))
        return sb
    }

    private fun unescape(s: String) = s.replace(Regex("""\\([\\`*_{}\[\]()#+\-.!|=~$>])"""), "$1")

    // ---- math ---------------------------------------------------------------------------------

    /** Not real typesetting: common LaTeX becomes Unicode, ^ and _ become super/subscripts. */
    private object Math {
        private val SYMBOLS = mapOf(
            "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε",
            "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
            "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π", "rho" to "ρ", "sigma" to "σ",
            "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
            "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π",
            "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
            "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓", "ast" to "∗",
            "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠", "approx" to "≈",
            "equiv" to "≡", "sim" to "∼", "simeq" to "≃", "propto" to "∝", "ll" to "≪", "gg" to "≫",
            "infty" to "∞", "partial" to "∂", "nabla" to "∇", "sum" to "Σ", "prod" to "Π", "int" to "∫",
            "iint" to "∬", "oint" to "∮", "sqrt" to "√", "in" to "∈", "notin" to "∉", "subset" to "⊂",
            "subseteq" to "⊆", "supset" to "⊃", "cup" to "∪", "cap" to "∩", "emptyset" to "∅", "varnothing" to "∅",
            "forall" to "∀", "exists" to "∃", "neg" to "¬", "land" to "∧", "lor" to "∨", "wedge" to "∧", "vee" to "∨",
            "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "Rightarrow" to "⇒", "Leftarrow" to "⇐",
            "leftrightarrow" to "↔", "Leftrightarrow" to "⇔", "iff" to "⇔", "implies" to "⇒", "mapsto" to "↦",
            "uparrow" to "↑", "downarrow" to "↓", "ldots" to "…", "cdots" to "⋯", "dots" to "…", "vdots" to "⋮",
            "angle" to "∠", "perp" to "⊥", "parallel" to "∥", "degree" to "°", "circ" to "∘", "hbar" to "ℏ",
            "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ", "aleph" to "ℵ", "langle" to "⟨", "rangle" to "⟩",
            "lfloor" to "⌊", "rfloor" to "⌋", "lceil" to "⌈", "rceil" to "⌉", "|" to "‖", "{" to "{", "}" to "}",
            "," to " ", ";" to " ", ":" to " ", "quad" to "  ", "qquad" to "    ", "!" to "", " " to " ",
            "sin" to "sin", "cos" to "cos", "tan" to "tan", "log" to "log", "ln" to "ln", "exp" to "exp",
            "lim" to "lim", "max" to "max", "min" to "min", "det" to "det",
        )
        private val BLACKBOARD = mapOf('R' to "ℝ", 'N' to "ℕ", 'Z' to "ℤ", 'Q' to "ℚ", 'C' to "ℂ", 'P' to "ℙ")

        private fun rewrite(src: String): String {
            var t = src
            t = Regex("""\\(?:label|tag)\{[^}]*\}""").replace(t, "")
            t = Regex("""\\(?:left|right|big|Big|bigg|Bigg|displaystyle|textstyle)\b\.?""").replace(t, "")
            t = Regex("""\\mathbb\{(\w)\}""").replace(t) { BLACKBOARD[it.groupValues[1][0]] ?: it.groupValues[1] }
            t = Regex("""\\(?:mathrm|mathbf|mathit|text|textrm|textbf|operatorname|boldsymbol|mbox|vec|hat|bar)\{([^{}]*)\}""")
                .replace(t) { it.groupValues[1] }
            val frac = Regex("""\\[dt]?frac\{([^{}]*)\}\{([^{}]*)\}""")
            val sqrt = Regex("""\\sqrt\{([^{}]*)\}""")
            // A single number, letter or symbol needs no parentheses.
            val atom = Regex("""√?\\?\w+""")
            fun wrap(s: String) = if (s.matches(atom)) s else "($s)"
            repeat(5) {
                t = frac.replace(t) { m -> wrap(m.groupValues[1]) + "/" + wrap(m.groupValues[2]) }
                t = sqrt.replace(t) { m -> "√" + wrap(m.groupValues[1]) }
            }
            t = Regex("""\\begin\{[a-zA-Z*]+\}(\{[^}]*\})?|\\end\{[a-zA-Z*]+\}""").replace(t, "")
            t = t.replace("\\\\", "\n").replace("&", " ")
            t = Regex("""\\([a-zA-Z]+|[|{},;:! ])""").replace(t) { SYMBOLS[it.groupValues[1]] ?: it.groupValues[1] }
            return t.lines().joinToString("\n") { it.trim().replace(Regex(" {3,}"), "  ") }.trim()
        }

        fun inline(tex: String, sb: SpannableStringBuilder) {
            val t = rewrite(tex)
            val start = sb.length
            var i = 0
            while (i < t.length) {
                val ch = t[i]
                if ((ch == '^' || ch == '_') && i + 1 < t.length) {
                    val (arg, next) = if (t[i + 1] == '{') {
                        var depth = 0; var j = i + 1
                        while (j < t.length) { if (t[j] == '{') depth++ else if (t[j] == '}' && --depth == 0) break; j++ }
                        t.substring(i + 2, minOf(j, t.length)) to j + 1
                    } else t.substring(i + 1, i + 2) to i + 2
                    val s = sb.length
                    inline(arg, sb)
                    sb.setSpan(if (ch == '^') SuperscriptSpan() else SubscriptSpan(), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(0.7f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = next
                } else {
                    if (ch != '{' && ch != '}') sb.append(ch)
                    i++
                }
            }
            sb.setSpan(TypefaceSpan("serif"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    // ---- blocks -------------------------------------------------------------------------------

    private sealed class Block {
        class Heading(val level: Int, val text: String) : Block()
        class Para(val text: String) : Block()
        class Code(val text: String) : Block()
        class MathBlock(val tex: String) : Block()
        object Rule : Block()
        class Quote(val title: String?, val body: List<Block>) : Block()
        class ListItem(val level: Int, val marker: String, val task: Boolean?, val text: String) : Block()
        class Table(val rows: List<List<String>>) : Block()
    }

    private class Md(val size: Float) {
        companion object {
            private val FENCE = Regex("""^\s*(```+|~~~+)""")
            private val HEADING = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
            private val RULE = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
            private val LIST = Regex("""^(\s*)([-*+]|\d+[.)])\s+(.*)$""")
            private val TASK = Regex("""^\[([ xX])\]\s+(.*)$""")
            private val TABLE_SEP = Regex("""^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")
            private val CALLOUT = Regex("""^\[!(\w+)\][+-]?\s*(.*)$""")

            private fun startsBlock(l: String) = l.isBlank() || FENCE.containsMatchIn(l) || HEADING.matches(l) ||
                RULE.matches(l) || l.trimStart().startsWith(">") || LIST.matches(l) || l.trimStart().startsWith("$$")

            private fun cells(l: String) = l.trim().removePrefix("|").removeSuffix("|").split(Regex("""(?<!\\)\|""")).map { it.trim() }

            fun parse(text: String): List<Block> {
                val lines = text.split("\n")
                val out = mutableListOf<Block>()
                var i = 0
                val listIndents = mutableListOf<Int>()
                while (i < lines.size) {
                    val l = lines[i]
                    if (!LIST.matches(l) && l.isNotBlank()) listIndents.clear()
                    when {
                        l.isBlank() -> i++
                        FENCE.containsMatchIn(l) -> {
                            val fence = FENCE.find(l)!!.groupValues[1].take(3)
                            val body = mutableListOf<String>()
                            i++
                            while (i < lines.size && !lines[i].trimStart().startsWith(fence)) body += lines[i++]
                            i++
                            out += Block.Code(body.joinToString("\n"))
                        }
                        l.trimStart().startsWith("$$") -> {
                            val first = l.trim().removePrefix("$$")
                            if (first.endsWith("$$")) { out += Block.MathBlock(first.removeSuffix("$$")); i++ }
                            else {
                                val body = mutableListOf(first)
                                i++
                                while (i < lines.size && !lines[i].trim().endsWith("$$")) body += lines[i++]
                                if (i < lines.size) body += lines[i++].trim().removeSuffix("$$")
                                out += Block.MathBlock(body.joinToString("\n"))
                            }
                        }
                        HEADING.matches(l) -> { val m = HEADING.find(l)!!; out += Block.Heading(m.groupValues[1].length, m.groupValues[2]); i++ }
                        RULE.matches(l) -> { out += Block.Rule; i++ }
                        l.trimStart().startsWith(">") -> {
                            val body = mutableListOf<String>()
                            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                                body += lines[i].trimStart().removePrefix(">").removePrefix(" "); i++
                            }
                            val m = CALLOUT.find(body.first().trim())
                            out += if (m != null) {
                                val title = m.groupValues[2].ifBlank { m.groupValues[1].replaceFirstChar { it.uppercase() } }
                                Block.Quote(title, parse(body.drop(1).joinToString("\n")))
                            } else Block.Quote(null, parse(body.joinToString("\n")))
                        }
                        LIST.matches(l) -> {
                            val m = LIST.find(l)!!
                            val indent = m.groupValues[1].replace("\t", "    ").length
                            while (listIndents.isNotEmpty() && listIndents.last() > indent) listIndents.removeAt(listIndents.lastIndex)
                            if (listIndents.isEmpty() || listIndents.last() < indent) listIndents += indent
                            var text = m.groupValues[3]
                            i++
                            // Lazy continuation lines.
                            while (i < lines.size && lines[i].isNotBlank() && !startsBlock(lines[i])) text += "\n" + lines[i++].trim()
                            val task = TASK.find(text)
                            val marker = m.groupValues[2].let { if (it[0].isDigit()) it.dropLast(1) + "." else "•" }
                            out += if (task != null) Block.ListItem(listIndents.size - 1, marker, task.groupValues[1] != " ", task.groupValues[2])
                            else Block.ListItem(listIndents.size - 1, marker, null, text)
                        }
                        l.contains('|') && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1]) && lines[i + 1].contains('-') -> {
                            val rows = mutableListOf(cells(l))
                            i += 2
                            while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) rows += cells(lines[i++])
                            out += Block.Table(rows)
                        }
                        else -> {
                            val body = mutableListOf(l)
                            i++
                            while (i < lines.size && !startsBlock(lines[i])) body += lines[i++]
                            out += Block.Para(body.joinToString("\n") { it.trim() })
                        }
                    }
                }
                return out
            }
        }

        private val gap = (size * 0.45f).toInt()
        private val body = paint(size)

        fun stack(blocks: List<Block>, width: Int): Piece {
            val pieces = blocks.mapIndexed { idx, b ->
                // Consecutive list items sit closer together.
                val tight = b is Block.ListItem && idx > 0 && blocks[idx - 1] is Block.ListItem
                (if (idx == 0) 0 else if (tight) gap / 3 else gap) to block(b, width)
            }
            return object : Piece {
                override val height = pieces.sumOf { it.first + it.second.height }
                override fun draw(c: Canvas, x: Float, y: Float) {
                    var yy = y
                    for ((g, p) in pieces) { yy += g; p.draw(c, x, yy); yy += p.height }
                }
            }
        }

        private fun block(b: Block, width: Int): Piece = when (b) {
            is Block.Para -> textPiece(inline(b.text), body, width)
            is Block.Heading -> heading(b, width)
            is Block.Code -> code(b.text, width)
            is Block.MathBlock -> textPiece(SpannableStringBuilder().also { Math.inline(b.tex, it) },
                paint(size * 1.1f), width, Layout.Alignment.ALIGN_CENTER)
            Block.Rule -> object : Piece {
                override val height = 6
                override fun draw(c: Canvas, x: Float, y: Float) { c.drawRect(x, y + 2, x + width, y + 4, line) }
            }
            is Block.Quote -> quote(b, width)
            is Block.ListItem -> listItem(b, width)
            is Block.Table -> table(b.rows, width)
        }

        private fun heading(b: Block.Heading, width: Int): Piece {
            val scale = when (b.level) { 1 -> 1.45f; 2 -> 1.3f; 3 -> 1.15f; 4 -> 1.05f; else -> 1f }
            val text = textPiece(inline(b.text), paint(size * scale, bold = true), width)
            val rule = when (b.level) { 1 -> 3; 2 -> 1; else -> 0 }
            return object : Piece {
                override val height = text.height + if (rule > 0) rule + 4 else 0
                override fun draw(c: Canvas, x: Float, y: Float) {
                    text.draw(c, x, y)
                    if (rule > 0) c.drawRect(x, y + text.height + 3, x + width, y + text.height + 3 + rule, line)
                }
            }
        }

        private fun code(text: String, width: Int): Piece {
            val pad = 6
            val inner = textPiece(text, paint(size * 0.78f, mono = true), width - 2 * pad)
            val frame = Paint(stroke).apply { strokeWidth = 1f }
            return object : Piece {
                override val height = inner.height + 2 * pad
                override fun draw(c: Canvas, x: Float, y: Float) {
                    c.drawRect(x + 0.5f, y + 0.5f, x + width - 0.5f, y + height - 0.5f, frame)
                    inner.draw(c, x + pad, y + pad)
                }
            }
        }

        private fun quote(b: Block.Quote, width: Int): Piece {
            val indent = 12
            val parts = mutableListOf<Block>()
            val title = b.title?.let { textPiece(inline(it), paint(size, bold = true), width - indent) }
            parts += b.body
            val inner = stack(parts, width - indent)
            return object : Piece {
                override val height = (title?.height?.plus(if (b.body.isEmpty()) 0 else gap) ?: 0) + inner.height
                override fun draw(c: Canvas, x: Float, y: Float) {
                    c.drawRect(x, y, x + 3, y + height, line)
                    var yy = y
                    title?.let { it.draw(c, x + indent, yy); yy += it.height + gap }
                    inner.draw(c, x + indent, yy)
                }
            }
        }

        private fun listItem(b: Block.ListItem, width: Int): Piece {
            val indent = (size * 1.1f).toInt() * b.level
            val markerW = when {
                b.task != null -> (size * 1.2f).toInt()
                b.marker == "•" -> (size * 0.9f).toInt()
                else -> body.measureText(b.marker + " ").toInt()
            }
            val text = SpannableStringBuilder(inline(b.text)).also {
                if (b.task == true) it.setSpan(StrikethroughSpan(), 0, it.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val content = textPiece(text, body, width - indent - markerW)
            val fm = body.fontMetrics
            val baseline = -fm.ascent
            return object : Piece {
                override val height = content.height
                override fun draw(c: Canvas, x: Float, y: Float) {
                    val mx = x + indent
                    when (b.task) {
                        null -> c.drawText(b.marker, mx, y + baseline, body)
                        else -> {
                            val s = size * 0.7f
                            val top = y + baseline - s
                            c.drawRect(mx + 1, top, mx + 1 + s, top + s, stroke)
                            if (b.task) {
                                val p = Paint(stroke).apply { strokeWidth = 3f }
                                c.drawLine(mx + 1 + s * 0.2f, top + s * 0.5f, mx + 1 + s * 0.45f, top + s * 0.8f, p)
                                c.drawLine(mx + 1 + s * 0.45f, top + s * 0.8f, mx + 1 + s * 0.85f, top + s * 0.15f, p)
                            }
                        }
                    }
                    content.draw(c, mx + markerW, y)
                }
            }
        }

        private fun table(rows: List<List<String>>, width: Int): Piece {
            val cols = rows.maxOf { it.size }
            val pad = 4
            val small = paint(size * 0.85f)
            val bold = paint(size * 0.85f, bold = true)
            // Columns share the width in proportion to their natural width, with a minimum.
            val natural = IntArray(cols) { col -> rows.maxOf { r -> r.getOrNull(col)?.let { small.measureText(it).toInt() } ?: 0 } + 2 * pad }
            val minW = (width / cols / 2).coerceAtLeast(24)
            val total = natural.sum()
            val colW = if (total <= width) natural.map { it + (width - total) / cols }.toIntArray()
            else natural.map { maxOf(minW, it * width / total) }.toIntArray()
            colW[cols - 1] = width - colW.take(cols - 1).sum()
            val cells = rows.mapIndexed { r, row ->
                List(cols) { col -> textPiece(inline(row.getOrNull(col) ?: ""), if (r == 0) bold else small, colW[col] - 2 * pad) }
            }
            val heights = cells.map { row -> row.maxOf { it.height } + 2 * pad }
            val grid = Paint(stroke).apply { strokeWidth = 1f }
            return object : Piece {
                override val height = heights.sum() + 1
                override fun draw(c: Canvas, x: Float, y: Float) {
                    var yy = y
                    for ((r, row) in cells.withIndex()) {
                        var xx = x
                        for ((col, cell) in row.withIndex()) { cell.draw(c, xx + pad, yy + pad); xx += colW[col] }
                        c.drawLine(x, yy + 0.5f, x + width, yy + 0.5f, if (r == 1) stroke else grid)
                        yy += heights[r]
                    }
                    c.drawLine(x, yy + 0.5f, x + width, yy + 0.5f, grid)
                    var xx = x + 0.5f
                    for (col in 0..cols) {
                        c.drawLine(minOf(xx, x + width - 0.5f), y, minOf(xx, x + width - 0.5f), yy, grid)
                        if (col < cols) xx += colW[col]
                    }
                }
            }
        }
    }
}
