package app.blockwork.domain.io

import app.blockwork.domain.Segment
import app.blockwork.domain.Workout
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

/**
 * Zwift workout files (.zwo). Supported elements: Warmup, Ramp, Cooldown, SteadyState,
 * IntervalsT, FreeRide, MaxEffort. Text events and cadence targets are ignored.
 */
object ZwoParser {

    fun parse(text: String, id: String, fallbackName: String): ImportResult {
        val root = parseXml(text).documentElement
        if (root.tagName != "workout_file") throw WorkoutImportException("Not a Zwift workout (.zwo) file")
        val sport = root.childText("sportType")?.lowercase()
        if (sport != null && sport != "bike") throw WorkoutImportException("Only bike workouts are supported (file is '$sport')")

        val workoutEl = root.children().firstOrNull { it.tagName == "workout" }
            ?: throw WorkoutImportException("File contains no <workout> section")
        val warnings = mutableListOf<String>()
        val segments = workoutEl.children().mapNotNull { el -> segment(el, warnings) }
        val workout = Workout(
            id = id,
            name = root.childText("name")?.takeIf { it.isNotBlank() } ?: fallbackName,
            description = root.childText("description").orEmpty(),
            segments = segments,
        )
        WorkoutValidator.validate(workout)
        return ImportResult(workout, warnings.distinct())
    }

    private fun segment(el: Element, warnings: MutableList<String>): Segment? = when (el.tagName) {
        "SteadyState" -> Segment.Steady(
            el.duration("Duration"),
            el.power("Power") ?: el.power("PowerLow") ?: el.power("PowerHigh") ?: missing(el, "Power"),
        )
        "Warmup", "Ramp" -> Segment.Ramp(
            el.duration("Duration"),
            el.power("PowerLow") ?: missing(el, "PowerLow"),
            el.power("PowerHigh") ?: missing(el, "PowerHigh"),
            if (el.tagName == "Warmup") "Warmup" else null,
        )
        "Cooldown" -> {
            val a = el.power("PowerLow") ?: missing(el, "PowerLow")
            val b = el.power("PowerHigh") ?: missing(el, "PowerHigh")
            // Files disagree on the attribute order; a cooldown always goes down.
            Segment.Ramp(el.duration("Duration"), maxOf(a, b), minOf(a, b), "Cooldown")
        }
        "IntervalsT" -> Segment.Repeat(
            el.attr("Repeat")?.toDoubleOrNull()?.roundToInt() ?: 1,
            listOf(
                Segment.Steady(el.duration("OnDuration"), el.power("OnPower") ?: missing(el, "OnPower"), "On"),
                Segment.Steady(el.duration("OffDuration"), el.power("OffPower") ?: missing(el, "OffPower"), "Off"),
            ),
        )
        "FreeRide", "MaxEffort" -> Segment.FreeRide(
            el.duration("Duration"),
            if (el.tagName == "MaxEffort") "Max effort" else "Free ride",
        )
        else -> {
            warnings += "Ignored unsupported element <${el.tagName}>"
            null
        }
    }

    private fun parseXml(text: String) = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            // No DTDs / external entities (XXE). Not every platform parser knows every feature.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        factory.newDocumentBuilder().parse(InputSource(StringReader(text)))
    } catch (e: Exception) {
        throw WorkoutImportException("File is not valid XML", e)
    }

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private fun Element.childText(tag: String): String? =
        children().firstOrNull { it.tagName.equals(tag, ignoreCase = true) }?.textContent?.trim()

    /** Attribute lookup, case-insensitive (files in the wild vary). */
    private fun Element.attr(name: String): String? {
        val attrs = attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            if (a.nodeName.equals(name, ignoreCase = true)) return a.nodeValue.trim()
        }
        return null
    }

    private fun Element.duration(name: String): Int =
        attr(name)?.toDoubleOrNull()?.roundToInt() ?: missing(this, name)

    private fun Element.power(name: String): Double? = attr(name)?.toDoubleOrNull()

    private fun missing(el: Element, attr: String): Nothing =
        throw WorkoutImportException("<${el.tagName}> is missing '$attr'")
}
