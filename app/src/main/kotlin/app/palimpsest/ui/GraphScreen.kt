package app.palimpsest.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.palimpsest.R
import app.palimpsest.ffi.EntryKind
import app.palimpsest.ffi.EntrySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Entries as dots and their links as lines. Tap a dot to see its links; hold a dot and drag
 * it onto another to link the two, or onto one it is linked with to unlink them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphScreen(ui: UiState, vm: AppViewModel) {
    // Entries without links are shown by default: they are what new links are drawn to.
    var showAll by remember { mutableStateOf(true) }
    var kinds by remember { mutableStateOf(setOf(EntryKind.NOTE, EntryKind.DIARY, EntryKind.TODO)) }
    var fit by remember { mutableIntStateOf(0) }
    val linked = remember(ui.links) { ui.links.flatMap { listOf(it.fromId, it.toId) }.toSet() }
    val nodes = remember(ui.allEntries, linked, showAll, kinds) {
        ui.allEntries.filter { it.trashedMs == null && it.kind in kinds && (showAll || it.entryId in linked) }
    }
    // Where each entry was placed, kept across new links so the picture moves, not jumps.
    val placed = remember { HashMap<String, Offset>() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.graph)) },
                navigationIcon = { IconButton(onClick = vm::closeGraph) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = { IconButton(onClick = { fit++ }) { Icon(FitIcon, stringResource(R.string.graph_fit)) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionsMenu(kinds) { kinds = it }
                FilterChip(showAll, onClick = { showAll = !showAll }, label = { Text(stringResource(R.string.graph_all)) })
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (nodes.isEmpty()) {
                    EmptyState(GraphIcon, R.string.graph, R.string.graph_empty)
                } else {
                    ForceGraph(nodes, ui.links.map { it.fromId to it.toId }, placed, fit, onOpen = vm::openLinked, onToggle = vm::toggleLink)
                }
            }
        }
    }
}

private val SECTION_LABELS = listOf(
    EntryKind.NOTE to R.string.section_notes,
    EntryKind.DIARY to R.string.section_diary,
    EntryKind.TODO to R.string.section_todo,
)

/** One chip for the three sections; it opens a list to tick them. At least one stays on. */
@Composable
private fun SectionsMenu(kinds: Set<EntryKind>, onChange: (Set<EntryKind>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val names = SECTION_LABELS.map { it.first to stringResource(it.second) }
    val label = if (kinds.size == SECTION_LABELS.size) stringResource(R.string.graph_sections_all)
    else names.filter { it.first in kinds }.joinToString(", ") { it.second }
    Box {
        FilterChip(
            selected = kinds.size < SECTION_LABELS.size,
            onClick = { open = true },
            label = { Text(label, maxLines = 1) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((kind, res) in SECTION_LABELS) {
                DropdownMenuItem(
                    text = { Text(stringResource(res)) },
                    leadingIcon = { Checkbox(checked = kind in kinds, onCheckedChange = null) },
                    onClick = { onChange(if (kind in kinds && kinds.size > 1) kinds - kind else kinds + kind) },
                )
            }
        }
    }
}

private const val K = 90f // preferred distance between linked dots, in dp

/** A layout being shown: dots move from [from] to [to] as the animation runs. */
private class Shown(val ids: List<String>, val from: FloatArray, val to: FloatArray)

@Composable
private fun ForceGraph(
    nodes: List<EntrySummary>,
    links: List<Pair<String, String>>,
    placed: HashMap<String, Offset>,
    fitRequest: Int,
    onOpen: (String) -> Unit,
    onToggle: (from: String, to: String) -> Unit,
) {
    val ids = nodes.map { it.entryId }
    val n = ids.size
    val index = remember(ids) { ids.withIndex().associate { it.value to it.index } }
    val edges = remember(links, index) {
        links.mapNotNull { (a, b) -> val i = index[a]; val j = index[b]; if (i != null && j != null) i to j else null }.distinct()
    }
    val pairs = remember(edges) { edges.flatMap { (a, b) -> listOf(a to b, b to a) }.toSet() }
    val degree = remember(edges, n) { IntArray(n).also { d -> for ((a, b) in edges) { d[a]++; d[b]++ } } }
    val neighbours = remember(edges, n) {
        Array(n) { mutableSetOf<Int>() }.also { s -> for ((a, b) in edges) { s[a] += b; s[b] += a } }
    }

    val density = LocalDensity.current.density
    var canvas by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var origin by remember { mutableStateOf(Offset.Unspecified) }
    var shown by remember { mutableStateOf<Shown?>(null) }
    val progress = remember { Animatable(1f) }

    fun fitTo(target: FloatArray) {
        if (canvas == IntSize.Zero || target.isEmpty()) return
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until target.size / 2) {
            minX = min(minX, target[2 * i]); maxX = max(maxX, target[2 * i])
            minY = min(minY, target[2 * i + 1]); maxY = max(maxY, target[2 * i + 1])
        }
        val w = (maxX - minX + K) * density
        val h = (maxY - minY + K) * density
        scale = (min(canvas.width / w, canvas.height / h) * 0.9f).coerceIn(0.25f, 2.5f)
        origin = Offset(
            canvas.width / 2f - (minX + maxX) / 2 * density * scale,
            canvas.height / 2f - (minY + maxY) / 2 * density * scale,
        )
    }

    // The first picture is computed before it is drawn; later changes (a new link, a filter)
    // move the dots there in a short animation.
    LaunchedEffect(ids, edges) {
        val before = shown
        val t = progress.value
        val previous = HashMap<String, Offset>()
        if (before != null) {
            for ((i, id) in before.ids.withIndex()) {
                previous[id] = Offset(
                    before.from[2 * i] + (before.to[2 * i] - before.from[2 * i]) * t,
                    before.from[2 * i + 1] + (before.to[2 * i + 1] - before.from[2 * i + 1]) * t,
                )
            }
        }
        val start = FloatArray(2 * n)
        for ((i, id) in ids.withIndex()) {
            val p = previous[id] ?: placed[id] ?: neighbourCentre(i, neighbours, ids, previous, placed) ?: spiral(i)
            start[2 * i] = p.x
            start[2 * i + 1] = p.y
        }
        val cold = before == null && ids.none { it in placed }
        val target = withContext(Dispatchers.Default) {
            pack(layout(start, edges, iterations = if (cold) 500 else 250, temperature = if (cold) K else K * 0.4f), edges)
        }
        for ((i, id) in ids.withIndex()) placed[id] = Offset(target[2 * i], target[2 * i + 1])
        if (before == null) {
            shown = Shown(ids, target, target)
            progress.snapTo(1f)
            fitTo(target)
        } else {
            shown = Shown(ids, start, target)
            progress.snapTo(0f)
            progress.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
        }
    }
    LaunchedEffect(fitRequest, canvas) { shown?.let { fitTo(it.to) } }

    val haptic = LocalHapticFeedback.current
    var picked by remember(ids) { mutableStateOf<Int?>(null) }
    // A link being drawn: from which dot, the finger, and the dot under it.
    var linkFrom by remember { mutableStateOf<Int?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    var linkTo by remember { mutableStateOf<Int?>(null) }
    val toggle by rememberUpdatedState(onToggle)
    val measurer = rememberTextMeasurer()
    val scheme = MaterialTheme.colorScheme
    val labelStyle = TextStyle(fontSize = 11.sp, color = scheme.onSurface)
    val context = LocalContext.current
    val labels = remember(nodes, labelStyle) {
        nodes.map { measurer.measure(it.displayTitle(context).take(28), labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }

    val s = shown
    if (s == null || s.ids != ids) {
        Box(Modifier.fillMaxSize().onSizeChanged { canvas = it }, contentAlignment = Alignment.Center) {
            if (s == null) CircularProgressIndicator()
        }
        if (s == null) return
    }
    val layoutNow = shown ?: return
    val sameIds = layoutNow.ids == ids
    fun world(i: Int): Offset {
        if (!sameIds) return Offset.Zero
        val t = progress.value
        return Offset(
            layoutNow.from[2 * i] + (layoutNow.to[2 * i] - layoutNow.from[2 * i]) * t,
            layoutNow.from[2 * i + 1] + (layoutNow.to[2 * i + 1] - layoutNow.from[2 * i + 1]) * t,
        )
    }
    fun screen(i: Int): Offset {
        val w = world(i)
        return Offset(origin.x + w.x * density * scale, origin.y + w.y * density * scale)
    }
    fun radius(i: Int) = (5f + 2.5f * sqrt(degree[i].toFloat())) * density
    fun dotAt(p: Offset, except: Int? = null): Int? {
        if (!sameIds) return null
        var best: Int? = null
        var bestD = Float.MAX_VALUE
        for (i in 0 until n) {
            if (i == except) continue
            val q = screen(i)
            val d = (q.x - p.x) * (q.x - p.x) + (q.y - p.y) * (q.y - p.y)
            val reach = radius(i) * scale + 18 * density
            if (d < reach * reach && d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }
    fun colorOf(e: EntrySummary) = when (e.kind) {
        EntryKind.NOTE -> scheme.primary
        EntryKind.DIARY -> scheme.tertiary
        EntryKind.TODO -> scheme.secondary
    }
    val unlinking = linkFrom != null && linkTo != null && (linkFrom!! to linkTo!!) in pairs

    Box(Modifier.fillMaxSize().onSizeChanged { canvas = it }) {
        if (!sameIds) return@Box
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (linkFrom != null) return@detectTransformGestures
                        val o = origin
                        val newScale = (scale * zoom).coerceIn(0.2f, 4f)
                        val z = newScale / scale
                        scale = newScale
                        origin = centroid + (o - centroid) * z + pan
                    }
                }
                .pointerInput(ids, edges) {
                    detectTapGestures(
                        onTap = { p -> picked = dotAt(p) },
                        // Double tap on empty space brings the whole graph back into view.
                        onDoubleTap = { p -> if (dotAt(p) == null) shown?.let { fitTo(it.to) } },
                    )
                }
                // Innermost: it sees the finger first and, once a dot is held, keeps it from panning.
                .pointerInput(ids, edges) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { p ->
                            val from = dotAt(p)
                            if (from != null) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                linkFrom = from
                                picked = from
                                finger = p
                            }
                        },
                        onDrag = { change, _ ->
                            val from = linkFrom ?: return@detectDragGesturesAfterLongPress
                            change.consume()
                            finger = change.position
                            val to = dotAt(change.position, except = from)
                            if (to != linkTo && to != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            linkTo = to
                        },
                        onDragEnd = {
                            val from = linkFrom
                            val to = linkTo
                            if (from != null && to != null) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                toggle(ids[from], ids[to])
                                picked = to
                            }
                            linkFrom = null
                            linkTo = null
                        },
                        onDragCancel = {
                            linkFrom = null
                            linkTo = null
                        },
                    )
                },
        ) {
            if (origin == Offset.Unspecified) origin = Offset(size.width / 2, size.height / 2)
            val focus = picked
            val lit = focus?.let { neighbours[it] + it }
            for ((a, b) in edges) {
                val on = focus == null || a == focus || b == focus
                val breaking = unlinking && ((a == linkFrom && b == linkTo) || (b == linkFrom && a == linkTo))
                drawLine(
                    when {
                        breaking -> scheme.error.copy(alpha = 0.4f)
                        on && focus != null -> scheme.primary
                        else -> scheme.outlineVariant.copy(alpha = if (on) 1f else 0.35f)
                    },
                    screen(a), screen(b), strokeWidth = (if (on && focus != null) 2.5f else 1.5f) * density,
                )
            }
            linkFrom?.let { from ->
                val end = linkTo?.let { screen(it) } ?: finger
                drawLine(
                    if (unlinking) scheme.error else scheme.primary, screen(from), end, strokeWidth = 2.5f * density,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10 * density, 6 * density)),
                )
            }
            for (i in 0 until n) {
                val on = lit == null || i in lit || i == linkTo
                val c = colorOf(nodes[i])
                val r = radius(i) * sqrt(scale).coerceIn(0.6f, 1.6f)
                drawCircle(if (on) c else c.copy(alpha = 0.25f), r, screen(i))
                if (i == focus || i == linkTo) {
                    drawCircle(if (i == linkTo && unlinking) scheme.error else scheme.onSurface, r + 3 * density, screen(i), style = Stroke(2 * density))
                }
                if (on && (scale > 0.7f || degree[i] >= 3 || i == focus || i == linkTo)) {
                    val l = labels[i]
                    val p = screen(i)
                    drawText(l, topLeft = Offset(p.x - l.size.width / 2f, p.y + r + 3 * density))
                }
            }
        }
        // What letting go will do, at the top where the finger does not cover it.
        val from = linkFrom
        if (from != null) {
            val to = linkTo
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = when {
                    to == null -> scheme.surfaceContainerHigh
                    unlinking -> scheme.errorContainer
                    else -> scheme.primaryContainer
                },
                tonalElevation = 3.dp,
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
            ) {
                Text(
                    when {
                        to == null -> stringResource(R.string.graph_drag_to, nodes[from].displayTitle(context))
                        unlinking -> stringResource(R.string.graph_unlink_pair, nodes[from].displayTitle(context), nodes[to].displayTitle(context))
                        else -> stringResource(R.string.graph_link_pair, nodes[from].displayTitle(context), nodes[to].displayTitle(context))
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        to == null -> scheme.onSurface
                        unlinking -> scheme.onErrorContainer
                        else -> scheme.onPrimaryContainer
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
        val card = picked?.takeIf { linkFrom == null && it < n }
        if (card != null) {
            val e = nodes[card]
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = scheme.surfaceContainerHigh,
                tonalElevation = 3.dp,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).fillMaxWidth(),
            ) {
                Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.displayTitle(context), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            pluralStringResource(R.plurals.links_count, degree[card], degree[card]),
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { onOpen(e.entryId) }) { Text(stringResource(R.string.open)) }
                }
            }
        } else if (linkFrom == null) {
            Text(
                stringResource(R.string.graph_hint),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
            )
        }
    }
}

private fun spiral(i: Int): Offset {
    val angle = i * 2.4f
    val r = K * sqrt(i + 1f)
    return Offset(r * cos(angle), r * sin(angle))
}

/** A dot that just appeared starts among its neighbours, if any of them is already placed. */
private fun neighbourCentre(
    i: Int,
    neighbours: Array<MutableSet<Int>>,
    ids: List<String>,
    previous: Map<String, Offset>,
    placed: Map<String, Offset>,
): Offset? {
    val known = neighbours[i].mapNotNull { previous[ids[it]] ?: placed[ids[it]] }
    if (known.isEmpty()) return null
    val c = known.fold(Offset.Zero) { a, b -> a + b } / known.size.toFloat()
    return c + spiral(i) / 20f // a small offset, so two newcomers do not sit on one spot
}

/** Fruchterman–Reingold with cooling, run to the end before the result is shown. */
private fun layout(start: FloatArray, edges: List<Pair<Int, Int>>, iterations: Int, temperature: Float): FloatArray {
    var p = start
    var t = temperature
    repeat(iterations) {
        p = step(p, edges, t)
        t = max(0.5f, t * 0.985f)
    }
    return p
}

/** One step: dots repel, links pull, a weak pull to the centre. */
private fun step(p: FloatArray, edges: List<Pair<Int, Int>>, temperature: Float): FloatArray {
    val n = p.size / 2
    val d = FloatArray(p.size)
    for (i in 0 until n) {
        for (j in i + 1 until n) {
            val dx = p[2 * i] - p[2 * j]
            val dy = p[2 * i + 1] - p[2 * j + 1]
            val dist = sqrt(max(dx * dx + dy * dy, 0.01f))
            val f = K * K / dist
            d[2 * i] += dx / dist * f
            d[2 * i + 1] += dy / dist * f
            d[2 * j] -= dx / dist * f
            d[2 * j + 1] -= dy / dist * f
        }
    }
    for ((a, b) in edges) {
        val dx = p[2 * a] - p[2 * b]
        val dy = p[2 * a + 1] - p[2 * b + 1]
        val dist = sqrt(max(dx * dx + dy * dy, 0.01f))
        val f = dist * dist / K
        d[2 * a] -= dx / dist * f
        d[2 * a + 1] -= dy / dist * f
        d[2 * b] += dx / dist * f
        d[2 * b + 1] += dy / dist * f
    }
    val out = FloatArray(p.size)
    for (i in 0 until n) {
        val gx = d[2 * i] - p[2 * i] * 0.08f
        val gy = d[2 * i + 1] - p[2 * i + 1] * 0.08f
        val len = sqrt(max(gx * gx + gy * gy, 0.0001f))
        val move = min(len, temperature)
        out[2 * i] = p[2 * i] + gx / len * move
        out[2 * i + 1] = p[2 * i + 1] + gy / len * move
    }
    return out
}

/**
 * Separate groups of linked entries never overlap: each group is a circle, and overlapping
 * circles are pushed apart until none do. Within a group nothing moves.
 */
private fun pack(p: FloatArray, edges: List<Pair<Int, Int>>): FloatArray {
    val n = p.size / 2
    val parent = IntArray(n) { it }
    fun root(i: Int): Int {
        var x = i
        while (parent[x] != x) {
            parent[x] = parent[parent[x]]
            x = parent[x]
        }
        return x
    }
    for ((a, b) in edges) parent[root(a)] = root(b)
    val groups = (0 until n).groupBy(::root).values.toList()
    val cx = FloatArray(groups.size)
    val cy = FloatArray(groups.size)
    val r = FloatArray(groups.size)
    for ((g, members) in groups.withIndex()) {
        cx[g] = members.sumOf { p[2 * it].toDouble() }.toFloat() / members.size
        cy[g] = members.sumOf { p[2 * it + 1].toDouble() }.toFloat() / members.size
        r[g] = members.maxOf { sqrt((p[2 * it] - cx[g]).let { x -> x * x } + (p[2 * it + 1] - cy[g]).let { y -> y * y }) } + K * 0.6f
    }
    val ox = cx.copyOf()
    val oy = cy.copyOf()
    for (round in 0 until 200) {
        var moved = false
        for (a in groups.indices) {
            for (b in a + 1 until groups.size) {
                var dx = cx[b] - cx[a]
                var dy = cy[b] - cy[a]
                var dist = sqrt(dx * dx + dy * dy)
                val need = r[a] + r[b]
                if (dist >= need) continue
                if (dist < 0.01f) {
                    dx = cos(b.toFloat()); dy = sin(b.toFloat()); dist = 1f
                }
                val push = (need - dist) / 2
                cx[a] -= dx / dist * push; cy[a] -= dy / dist * push
                cx[b] += dx / dist * push; cy[b] += dy / dist * push
                moved = true
            }
        }
        if (!moved) break
    }
    val out = p.copyOf()
    for ((g, members) in groups.withIndex()) {
        for (i in members) {
            out[2 * i] += cx[g] - ox[g]
            out[2 * i + 1] += cy[g] - oy[g]
        }
    }
    return out
}
