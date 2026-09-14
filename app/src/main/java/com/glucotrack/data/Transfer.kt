package com.glucotrack.data

/**
 * A plain-text export of everything one phone has recorded, so two phones can be reconciled.
 *
 * This app has no network permission and no server, which means two installations accumulate
 * separate, partial records: whoever scanned is the only one who has those readings. Rather than
 * weaken that by adding sync, a phone can write its whole record to a file and the other phone
 * can merge it in.
 *
 * Merging is safe to repeat. Readings are identified by sensor serial plus the sensor's own
 * clock, so the same measurement imported twice overwrites itself instead of double-counting —
 * the same property that lets repeated scans accumulate cleanly.
 *
 * The format is sectioned CSV: readable in a text editor, diffable, and parseable without a
 * serialization library, which keeps the export independent of how the app happens to store
 * things internally.
 */
object Transfer {

    const val FORMAT_VERSION = 1
    private const val HEADER = "# GlucoTrack export v"

    private const val SECTION_READINGS = "[readings]"
    private const val SECTION_MEALS = "[meals]"
    private const val SECTION_SENSORS = "[sensors]"

    data class Snapshot(
        val readings: List<GlucoseReading> = emptyList(),
        val meals: List<NutritionEntry> = emptyList(),
        val sensors: List<SensorRecord> = emptyList(),
    )

    /** What an import actually changed, so the user is told rather than left guessing. */
    data class MergeSummary(
        val readingsAdded: Int,
        val readingsAlreadyPresent: Int,
        val mealsAdded: Int,
        val mealsAlreadyPresent: Int,
        val sensorsAdded: Int,
    ) {
        val nothingNew: Boolean get() = readingsAdded == 0 && mealsAdded == 0 && sensorsAdded == 0
    }

    fun export(snapshot: Snapshot, exportedAt: Long): String = buildString {
        appendLine("$HEADER$FORMAT_VERSION")
        appendLine("# exported at epoch millis: $exportedAt")
        appendLine("# Readings are keyed by sensor serial and the sensor's own clock, so")
        appendLine("# importing this file more than once is harmless.")
        appendLine()

        appendLine(SECTION_READINGS)
        appendLine("sensorSerial,minutesSinceStart,timestamp,mgdl,fromTrend")
        snapshot.readings.sortedBy { it.timestamp }.forEach {
            appendLine(
                row(
                    it.sensorSerial,
                    it.minutesSinceStart.toString(),
                    it.timestamp.toString(),
                    it.mgdl.toString(),
                    it.fromTrend.toString(),
                )
            )
        }
        appendLine()

        appendLine(SECTION_MEALS)
        appendLine("timestamp,description,carbsGrams,mealType,notes")
        snapshot.meals.sortedBy { it.timestamp }.forEach {
            appendLine(
                row(
                    it.timestamp.toString(),
                    it.description,
                    it.carbsGrams?.toString() ?: "",
                    it.mealType.name,
                    it.notes ?: "",
                )
            )
        }
        appendLine()

        appendLine(SECTION_SENSORS)
        appendLine("serial,activatedAt,maxLifeMinutes,lastScanAt,lastState")
        snapshot.sensors.forEach {
            appendLine(
                row(
                    it.serial,
                    it.activatedAt.toString(),
                    it.maxLifeMinutes.toString(),
                    it.lastScanAt.toString(),
                    it.lastState,
                )
            )
        }
    }

    /**
     * Parses an exported file.
     *
     * Malformed rows are skipped rather than failing the whole import: a partially readable file
     * from the other phone is worth more than an error message, and the alternative is losing
     * good readings because one line was mangled in transit.
     *
     * @throws IllegalArgumentException if this does not look like a GlucoTrack export at all.
     */
    fun parse(text: String): Snapshot {
        val lines = text.lineSequence().toList()
        require(lines.any { it.startsWith(HEADER) }) {
            "This doesn't look like a GlucoTrack export file."
        }

        val readings = mutableListOf<GlucoseReading>()
        val meals = mutableListOf<NutritionEntry>()
        val sensors = mutableListOf<SensorRecord>()
        var section = ""

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[")) {
                section = line
                continue
            }
            // Column headers repeat the field names; skip them.
            if (line.startsWith("sensorSerial,") || line.startsWith("timestamp,") ||
                line.startsWith("serial,")
            ) continue

            val f = parseRow(line)
            runCatching {
                when (section) {
                    SECTION_READINGS -> if (f.size >= 5) readings += GlucoseReading(
                        sensorSerial = f[0],
                        minutesSinceStart = f[1].toInt(),
                        timestamp = f[2].toLong(),
                        mgdl = f[3].toDouble(),
                        fromTrend = f[4].toBooleanStrict(),
                    )

                    SECTION_MEALS -> if (f.size >= 5) meals += NutritionEntry(
                        timestamp = f[0].toLong(),
                        description = f[1],
                        carbsGrams = f[2].takeIf { it.isNotEmpty() }?.toInt(),
                        mealType = runCatching { MealType.valueOf(f[3]) }
                            .getOrDefault(MealType.OTHER),
                        notes = f[4].takeIf { it.isNotEmpty() },
                    )

                    SECTION_SENSORS -> if (f.size >= 5) sensors += SensorRecord(
                        serial = f[0],
                        activatedAt = f[1].toLong(),
                        maxLifeMinutes = f[2].toInt(),
                        lastScanAt = f[3].toLong(),
                        lastState = f[4],
                    )
                }
            }
        }
        return Snapshot(readings, meals, sensors)
    }

    private fun row(vararg fields: String) = fields.joinToString(",") { escape(it) }

    /** Quotes a field if it contains anything that would break the row apart. */
    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private fun parseRow(line: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    out += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        out += current.toString()
        return out
    }

    /**
     * Natural identity for a logged meal.
     *
     * Database row ids are assigned per device, so they cannot be used to recognise the same
     * meal arriving from the other phone. The time it was eaten plus what it was serves instead.
     */
    fun mealKey(entry: NutritionEntry): Pair<Long, String> =
        entry.timestamp to entry.description.trim().lowercase()
}
