package app.palimpsest.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle

/** Colours of highlighted code, from the theme. */
class CodeColors(val keyword: Color, val string: Color, val number: Color, val comment: Color)

/**
 * What the highlighter needs to know about a language: no grammar, only tokens. [quotes] open
 * strings that end on the same character (unless escaped); [multiline] ones may span lines.
 */
private class Lang(
    keywords: String,
    val literals: Set<String> = setOf("true", "false", "null"),
    val line: List<String> = listOf("//"),
    val block: Pair<String, String>? = "/*" to "*/",
    val quotes: String = "\"'",
    val multiline: String = "",
    val triple: Boolean = false,
    /** `'a'` is a character, a lone `'` (a Rust lifetime) is not a string. */
    val charLiterals: Boolean = false,
) {
    val keywords = keywords.split(' ').toSet()
}

private val C_LIKE = "if else for while do switch case default break continue return goto sizeof typedef struct union enum " +
    "static const extern volatile register inline void char short int long float double signed unsigned bool auto"

private val LANGS: Map<String, Lang> = run {
    val kotlin = Lang(
        "package import class interface object fun val var if else when for while do return break continue throw try catch " +
            "finally in is as typealias this super private public protected internal override open abstract sealed data " +
            "enum inner companion lateinit const suspend inline operator infix vararg by get set init constructor where out reified",
        quotes = "\"'", triple = true,
    )
    val java = Lang(
        "package import class interface enum extends implements new if else for while do switch case default return break " +
            "continue throw throws try catch finally this super private public protected static final abstract native " +
            "synchronized transient volatile void boolean byte char short int long float double instanceof var record yield",
    )
    val js = Lang(
        "var let const function return if else for while do switch case default break continue throw try catch finally new " +
            "delete typeof instanceof in of class extends super this import export from as async await yield static get set " +
            "interface type enum implements private public protected readonly declare namespace keyof",
        literals = setOf("true", "false", "null", "undefined", "NaN"),
        quotes = "\"'`", multiline = "`",
    )
    val python = Lang(
        "def class return if elif else for while break continue pass import from as with try except finally raise lambda " +
            "yield global nonlocal assert del in is not and or async await match case",
        literals = setOf("True", "False", "None"),
        line = listOf("#"), block = null, triple = true,
    )
    val rust = Lang(
        "as async await break const continue crate dyn else enum extern fn for if impl in let loop match mod move mut pub " +
            "ref return self Self static struct super trait type unsafe use where while u8 u16 u32 u64 u128 usize i8 i16 " +
            "i32 i64 i128 isize f32 f64 bool char str",
        literals = setOf("true", "false", "None", "Some", "Ok", "Err"),
        quotes = "\"", charLiterals = true,
    )
    val c = Lang("$C_LIKE #include #define #ifdef #ifndef #endif #if #else #pragma", literals = setOf("true", "false", "NULL", "nullptr"))
    val cpp = Lang(
        "$C_LIKE class namespace template typename public private protected virtual override new delete this using " +
            "try catch throw constexpr noexcept nullptr operator friend explicit mutable #include #define #ifdef #ifndef " +
            "#endif #if #else #pragma",
        literals = setOf("true", "false", "NULL", "nullptr"),
    )
    val go = Lang(
        "break case chan const continue default defer else fallthrough for func go goto if import interface map package " +
            "range return select struct switch type var int int8 int16 int32 int64 uint uint8 uint16 uint32 uint64 float32 " +
            "float64 string bool byte rune error",
        literals = setOf("true", "false", "nil", "iota"),
        quotes = "\"'`", multiline = "`",
    )
    val swift = Lang(
        "class struct enum protocol extension func var let if else guard switch case default for while repeat return break " +
            "continue import in where throw throws try catch do self Self init deinit static private public internal " +
            "fileprivate open override mutating async await some any",
        literals = setOf("true", "false", "nil"),
        quotes = "\"", triple = true,
    )
    val csharp = Lang(
        "using namespace class struct interface enum public private protected internal static readonly const void var new " +
            "if else for foreach while do switch case default return break continue throw try catch finally this base " +
            "override virtual abstract sealed async await in out ref int long float double bool string char object",
    )
    val shell = Lang(
        "if then else elif fi for while until do done case esac in function return export local readonly echo exit set unset source",
        literals = emptySet(), line = listOf("#"), block = null,
    )
    val sql = Lang(
        "select from where insert into values update set delete create table drop alter add index primary key foreign " +
            "references join left right inner outer on group by order having limit offset as and or not in is like distinct " +
            "union all exists case when then else end begin commit rollback integer text real blob varchar",
        literals = setOf("true", "false", "null"),
        line = listOf("--"), quotes = "'\"",
    )
    val json = Lang("", line = emptyList(), block = null, quotes = "\"")
    val yaml = Lang("", literals = setOf("true", "false", "null", "yes", "no", "on", "off"), line = listOf("#"), block = null)
    val lua = Lang(
        "and break do else elseif end for function goto if in local not or repeat return then until while",
        literals = setOf("true", "false", "nil"), line = listOf("--"), block = "--[[" to "]]",
    )
    val ruby = Lang(
        "def class module if elsif else unless case when while until for in do end return yield begin rescue ensure raise " +
            "require include extend attr_accessor self super then",
        literals = setOf("true", "false", "nil"), line = listOf("#"), block = null,
    )
    buildMap {
        listOf("kotlin", "kt", "kts").forEach { put(it, kotlin) }
        listOf("java").forEach { put(it, java) }
        listOf("js", "javascript", "jsx", "ts", "typescript", "tsx", "mjs").forEach { put(it, js) }
        listOf("python", "py").forEach { put(it, python) }
        listOf("rust", "rs").forEach { put(it, rust) }
        listOf("c", "h").forEach { put(it, c) }
        listOf("cpp", "c++", "cc", "cxx", "hpp").forEach { put(it, cpp) }
        listOf("go", "golang").forEach { put(it, go) }
        listOf("swift").forEach { put(it, swift) }
        listOf("cs", "csharp", "c#").forEach { put(it, csharp) }
        listOf("sh", "bash", "shell", "zsh", "console").forEach { put(it, shell) }
        listOf("sql", "sqlite").forEach { put(it, sql) }
        listOf("json").forEach { put(it, json) }
        listOf("yaml", "yml", "toml").forEach { put(it, yaml) }
        listOf("lua").forEach { put(it, lua) }
        listOf("ruby", "rb").forEach { put(it, ruby) }
    }
}

/** Whether [lang] (a fence's info string) is one [highlight] knows. */
fun highlights(lang: String) = lang in LANGS

/**
 * Code coloured by token: keywords, strings, numbers, comments. Without a known [lang] the
 * text comes back as it is. A lexer, not a parser: a wrong guess only costs a colour.
 */
fun highlight(code: String, lang: String, colors: CodeColors): AnnotatedString {
    val l = LANGS[lang] ?: return AnnotatedString(code)
    val caseless = l === LANGS["sql"]
    return buildAnnotatedString {
        var i = 0
        fun span(style: SpanStyle, end: Int) {
            withStyle(style) { append(code, i, end) }
            i = end
        }
        val comment = SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)
        val string = SpanStyle(color = colors.string)
        while (i < code.length) {
            val c = code[i]
            val block = l.block
            when {
                block != null && code.startsWith(block.first, i) -> {
                    val close = code.indexOf(block.second, i + block.first.length)
                    span(comment, if (close < 0) code.length else close + block.second.length)
                }
                l.line.any { code.startsWith(it, i) } && (l !== LANGS["sh"] || i == 0 || code[i - 1].isWhitespace()) -> {
                    val nl = code.indexOf('\n', i)
                    span(comment, if (nl < 0) code.length else nl)
                }
                l.triple && (code.startsWith("\"\"\"", i) || code.startsWith("'''", i)) -> {
                    val q = code.substring(i, i + 3)
                    val close = code.indexOf(q, i + 3)
                    span(string, if (close < 0) code.length else close + 3)
                }
                l.charLiterals && c == '\'' -> {
                    val m = CHAR_LITERAL.matchAt(code, i)
                    if (m != null) span(string, m.range.last + 1) else { append(c); i++ }
                }
                c in l.quotes -> span(string, stringEnd(code, i, c in l.multiline))
                c.isDigit() && (i == 0 || !code[i - 1].isLetterOrDigit() && code[i - 1] != '_') -> {
                    var e = i + 1
                    while (e < code.length && (code[e].isLetterOrDigit() || code[e] == '_' || code[e] == '.' && e + 1 < code.length && code[e + 1].isDigit())) e++
                    span(SpanStyle(color = colors.number), e)
                }
                c.isLetter() || c == '_' || c == '#' && (i == 0 || code[i - 1] == '\n') -> {
                    var e = i + 1
                    while (e < code.length && (code[e].isLetterOrDigit() || code[e] == '_')) e++
                    val word = code.substring(i, e)
                    val key = if (caseless) word.lowercase() else word
                    when (key) {
                        in l.keywords -> span(SpanStyle(color = colors.keyword), e)
                        in l.literals -> span(SpanStyle(color = colors.number), e)
                        else -> { append(word); i = e }
                    }
                }
                else -> { append(c); i++ }
            }
        }
    }
}

private val CHAR_LITERAL = Regex("""'(\\[^\n]{1,10}?|[^\\'\n])'""")

/** The index after the string opened by the quote at [start]; an unclosed one ends with its line. */
private fun stringEnd(code: String, start: Int, multiline: Boolean): Int {
    val quote = code[start]
    var e = start + 1
    while (e < code.length) {
        when (code[e]) {
            '\\' -> e++
            quote -> return e + 1
            '\n' -> if (!multiline) return e
        }
        e++
    }
    return code.length
}
