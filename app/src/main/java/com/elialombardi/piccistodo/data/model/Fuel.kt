package com.elialombardi.piccistodo.data.model

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.math.BigInteger
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

enum class FuelType(val wire: String) {
    GPL("gpl"), BENZINA("benzina");
    companion object {
        fun parse(value: String) = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("Invalid fuel type")
    }
}

data class FuelRequest(val fillType: String) {
    init { FuelType.parse(fillType) }
}

data class FuelRecord(val id: String, val userId: BigInteger, val fillType: FuelType, val createdAt: OffsetDateTime) {
    fun localDateTime(zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        createdAt.atZoneSameInstant(zone).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale))
}

data class FuelStats(val totalFills: Int, val gplCount: Int, val benzinaCount: Int,
                     val fillsUntilBenzina: Int, val nextFillType: FuelType) {
    init {
        require(totalFills >= 0 && gplCount >= 0 && benzinaCount >= 0)
        require(gplCount.toLong() + benzinaCount == totalFills.toLong())
        require(fillsUntilBenzina in 0..4)
        require(nextFillType == if (fillsUntilBenzina == 0) FuelType.BENZINA else FuelType.GPL)
        require(gplCount >= 4 - fillsUntilBenzina)
        if (totalFills == 0) require(fillsUntilBenzina == 4)
        if (benzinaCount == 0) require(fillsUntilBenzina == (4 - gplCount).coerceAtLeast(0))
    }
}

class FuelDataException : Exception()

/** Gson's default Kotlin decoding silently supplies zero/null for absent fields; do not use it here. */
object FuelDecoder {
    private fun JsonObject.text(name: String): String {
        val value = get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: throw FuelDataException()
        if (!value.isString) throw FuelDataException()
        return value.asString
    }
    private fun JsonObject.number(name: String): BigInteger {
        val value = get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: throw FuelDataException()
        if (!value.isNumber || !value.asString.matches(Regex("0|[1-9][0-9]*"))) throw FuelDataException()
        return value.asString.toBigInteger()
    }
    private inline fun <T> decode(block: () -> T): T = try { block() } catch (_: Exception) { throw FuelDataException() }
    fun record(json: JsonElement): FuelRecord = decode {
        val obj = json.asJsonObject
        val id = obj.text("id")
        require(UUID.fromString(id).toString().equals(id, ignoreCase = true))
        val user = obj.number("userId")
        require(user.bitLength() <= 64)
        FuelRecord(id, user, FuelType.parse(obj.text("fillType")), OffsetDateTime.parse(obj.text("createdAt")))
    }
    fun records(json: JsonElement): List<FuelRecord> = decode {
        json.asJsonArray.map(::record).also { records -> require(records.map { it.id }.distinct().size == records.size) }
            .sortedByDescending { it.createdAt.toInstant() }
    }
    fun stats(json: JsonElement): FuelStats = decode {
        val obj = json.asJsonObject
        FuelStats(obj.number("totalFills").intValueExact(), obj.number("gplCount").intValueExact(),
            obj.number("benzinaCount").intValueExact(), obj.number("fillsUntilBenzina").intValueExact(),
            FuelType.parse(obj.text("nextFillType")))
    }
    fun consistent(stats: FuelStats, records: List<FuelRecord>): Boolean {
        val gpl = records.count { it.fillType == FuelType.GPL }
        // Equal timestamps have no ordering guarantee; avoid inferring their cycle order.
        val lastBenzina = records.filter { it.fillType == FuelType.BENZINA }.maxOfOrNull { it.createdAt.toInstant() }
        val ambiguous = lastBenzina != null && records.any { it.fillType == FuelType.GPL && it.createdAt.toInstant() == lastBenzina }
        val since = records.count { it.fillType == FuelType.GPL && (lastBenzina == null || it.createdAt.toInstant() > lastBenzina) }
        return stats.totalFills == records.size && stats.gplCount == gpl && stats.benzinaCount == records.size - gpl &&
            (ambiguous || stats.fillsUntilBenzina == (4 - since).coerceAtLeast(0))
    }
}

