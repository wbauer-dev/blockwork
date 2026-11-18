package app.blockwork.domain.io

import app.blockwork.domain.Workout

data class ImportResult(val workout: Workout, val warnings: List<String> = emptyList())

/** Detects the file type (extension first, then content) and parses it. */
object WorkoutImporter {
    const val MAX_FILE_CHARS = 1_000_000

    fun import(fileName: String, text: String, id: String, userFtp: Int): ImportResult {
        if (text.length > MAX_FILE_CHARS) throw WorkoutImportException("File is too large")
        val content = text.removePrefix("﻿").trim()
        if (content.isEmpty()) throw WorkoutImportException("File is empty")
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val baseName = fileName.substringBeforeLast('.').ifBlank { "Imported workout" }
        return when {
            ext == "zwo" -> ZwoParser.parse(content, id, baseName)
            ext == "mrc" -> ErgParser.parse(content, id, baseName, userFtp, isMrc = true)
            ext == "erg" -> ErgParser.parse(content, id, baseName, userFtp, isMrc = false)
            ext == "json" -> ImportResult(WorkoutJson.decode(content, id))
            content.startsWith("<") -> ZwoParser.parse(content, id, baseName)
            content.startsWith("{") -> ImportResult(WorkoutJson.decode(content, id))
            content.contains("[COURSE HEADER]", ignoreCase = true) ->
                ErgParser.parse(content, id, baseName, userFtp, isMrc = content.contains("PERCENT", ignoreCase = true))
            else -> throw WorkoutImportException("Unknown file type – supported: .zwo, .erg, .mrc, .json")
        }
    }
}
