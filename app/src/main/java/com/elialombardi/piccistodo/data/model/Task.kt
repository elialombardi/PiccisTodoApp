package com.elialombardi.piccistodo.data.model

import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Currency
import java.util.Locale
import java.util.UUID

data class Project(val id: String, val name: String, val description: String? = null)
data class Task(
    val id: String,
    val projectId: String? = null,
    val projectName: String? = null,
    val name: String,
    val cost: BigDecimal,
    val date: String,
    val sentOn: String? = null,
    val description: String? = null
) {
    val isSent: Boolean get() = !sentOn.isNullOrEmpty()
    fun writable(sentDate: String = sentOn.orEmpty()) = TaskRequest(
        projectId.orEmpty(), name, cost, date, sentDate, description.orEmpty()
    )
}

data class TaskRequest(
    val projectId: String, val name: String, val cost: BigDecimal,
    val date: String, val sentOn: String, val description: String
)
data class ProjectRequest(val name: String, val description: String)
data class TaskSummary(val pendingCount: Int, val pendingTotal: BigDecimal, val totalCount: Int)

fun List<Task>.summary(): TaskSummary {
    val pending = filterNot { it.isSent }
    return TaskSummary(pending.size, pending.fold(BigDecimal.ZERO) { sum, task -> sum + task.cost }, size)
}
fun List<Task>.pendingByMonth(): Map<String, List<Task>> = filterNot { it.isSent }
    .sortedWith(compareBy<Task> { it.date }.thenBy { it.id }).groupBy { it.date.take(7) }
fun List<Task>.history(): List<Task> = sortedWith(compareByDescending<Task> { it.date }.thenByDescending { it.id })
fun eur(value: BigDecimal, locale: Locale = Locale.getDefault()): String = NumberFormat.getCurrencyInstance(locale).apply {
    currency = Currency.getInstance("EUR")
    minimumFractionDigits = 2
    maximumFractionDigits = 2
}.format(value)

fun validDate(value: String): Boolean = value.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) &&
    runCatching { LocalDate.parse(value); true }.getOrDefault(false)
fun validProjectId(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value.lowercase(Locale.ROOT) }.getOrDefault(false)
fun parseCost(value: String): BigDecimal? = value.trim().replace(',', '.').let {
    if (!it.matches(Regex("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)"))) null
    else it.toBigDecimalOrNull()?.takeIf { number -> number.toDouble().isFinite() }
}

data class TaskDraft(
    val projectId: String = "", val name: String = "", val cost: String = "10",
    val date: String = LocalDate.now().toString(), val sentOn: String = "", val description: String = ""
) {
    fun errors(projects: List<Project>): Set<String> = buildSet {
        if (!validProjectId(projectId) || projects.none { it.id == projectId }) add("projectId")
        if (name.isBlank()) add("name")
        if (parseCost(cost) == null) add("cost")
        if (!validDate(date)) add("date")
        if (sentOn.isNotEmpty() && !validDate(sentOn)) add("sentOn")
    }
    fun request() = TaskRequest(projectId, name.trim(), requireNotNull(parseCost(cost)), date, sentOn, description.trim())
    companion object {
        fun from(task: Task) = TaskDraft(task.projectId.orEmpty(), task.name, task.cost.toPlainString(), task.date, task.sentOn.orEmpty(), task.description.orEmpty())
    }
}
