package com.elialombardi.piccistodo.data.model

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

enum class NotesType(val wire: String) {
    BUY("buy_list"), TASKS("tasks_list"), DIARY("diary");
    companion object { fun parse(value: String) = entries.firstOrNull { it.wire == value } ?: throw NotesDataException() }
}
data class NotesStyle(val bold: Boolean = false, val italic: Boolean = false, val textColor: String = "#1f2937",
                      val backgroundColor: String = "#ffffff", val fontSize: Int = 16)
data class NotesItem(val id: Long, val folderId: Long, val position: Int, val title: String,
                     val content: String, val description: String, val endDate: String?, val completed: Boolean,
                     val style: NotesStyle, val createdAt: OffsetDateTime, val updatedAt: OffsetDateTime) {
    fun localUpdated(zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()) =
        updatedAt.atZoneSameInstant(zone).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale))
}
data class NotesFolder(val id: Long, val name: String, val type: NotesType, val position: Int, val parentId: Long?,
                       val children: List<NotesFolder>, val notes: List<NotesItem>)
data class NotesFolderRow(val folder: NotesFolder, val depth: Int, val path: String)
data class NotesSiblingOrder(val parentId: Long?, val ids: List<Long>)
fun List<NotesFolder>.folderSiblings(parentId: Long?): List<NotesFolder> =
    if (parentId == null) this else folderRows().firstOrNull { it.folder.id == parentId }?.folder?.children.orEmpty()

fun movedNotesIds(ids: List<Long>, from: Long, to: Long): List<Long> {
    val source = ids.indexOf(from)
    val destination = ids.indexOf(to)
    if (source < 0 || destination < 0 || source == destination) return ids
    return ids.toMutableList().apply { add(destination, removeAt(source)) }
}

/** Preview only: sibling moves carry their complete subtree without changing parent IDs. */
fun List<NotesFolder>.withFolderOrder(order: NotesSiblingOrder): List<NotesFolder> {
    fun apply(rows: List<NotesFolder>, parent: Long?): List<NotesFolder> {
        val nested = rows.map { it.copy(children = apply(it.children, it.id)) }
        return if (parent == order.parentId && order.ids.size == nested.size && order.ids.toSet() == nested.map { it.id }.toSet()) {
            val byId = nested.associateBy { it.id }
            order.ids.map { byId.getValue(it) }
        } else nested
    }
    return apply(this, null)
}

fun List<NotesFolder>.folderRows(): List<NotesFolderRow> = buildList {
    fun visit(folders: List<NotesFolder>, depth: Int, parent: String) {
        folders.forEach { folder ->
            val path = if (parent.isEmpty()) folder.name else "$parent / ${folder.name}"
            add(NotesFolderRow(folder, depth, path))
            visit(folder.children, depth + 1, path)
        }
    }
    visit(this@folderRows, 0, "")
}
fun notesDateValid(value: String) = value.isEmpty() ||
    (value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) && runCatching { LocalDate.parse(value) }.isSuccess)
fun notesPosition(value: String): Int? = value.takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull()
fun notesColorValid(value: String) = value.matches(Regex("#[0-9a-fA-F]{6}"))

data class NotesFolderDraft(val name: String = "", val type: NotesType = NotesType.DIARY, val position: String = "0") {
    fun errors() = buildSet {
        if (name.trim().isEmpty() || name.trim().length > 100) add("name")
        if (notesPosition(position) == null) add("position")
    }
}
data class NotesItemDraft(val folderId: Long, val title: String = "", val position: String = "0",
                          val content: String = "", val description: String = "", val endDate: String = "",
                          val completed: Boolean = false, val style: NotesStyle = NotesStyle()) {
    fun errors(folders: List<NotesFolderRow>) = buildSet {
        if (title.trim().isEmpty() || title.trim().length > 200) add("title")
        if (notesPosition(position) == null) add("position")
        if (folders.none { it.folder.id == folderId }) add("folderId")
        if (!notesDateValid(endDate)) add("endDate")
        if (!notesColorValid(style.textColor) || !notesColorValid(style.backgroundColor) || style.fontSize <= 0) add("style")
    }
    companion object {
        fun from(item: NotesItem) = NotesItemDraft(item.folderId, item.title, item.position.toString(), item.content,
            item.description, item.endDate.orEmpty(), item.completed, item.style)
    }
}

object NotesWrites {
    fun position(value: Int) = JsonObject().apply { require(value >= 0); addProperty("position", value) }
    fun completion(value: Boolean) = JsonObject().apply { addProperty("completed", value) }
    fun folder(draft: NotesFolderDraft, original: NotesFolderDraft?, parentId: Long?): JsonObject = JsonObject().apply {
        require(draft.errors().isEmpty())
        if (original == null || draft.name.trim() != original.name) addProperty("name", draft.name.trim())
        if (original == null || draft.type != original.type) addProperty("type", draft.type.wire)
        if (original == null || draft.position != original.position) addProperty("position", notesPosition(draft.position))
        if (original == null && parentId != null) { require(parentId > 0); addProperty("parentId", parentId) }
    }
    fun item(draft: NotesItemDraft, original: NotesItemDraft?, type: NotesType): JsonObject = JsonObject().apply {
        require(draft.folderId > 0 && draft.title.trim().isNotEmpty() && draft.title.trim().length <= 200)
        require(notesPosition(draft.position) != null && notesDateValid(draft.endDate))
        if (original == null || draft.folderId != original.folderId) addProperty("folderId", draft.folderId)
        if (original == null || draft.title.trim() != original.title) addProperty("title", draft.title.trim())
        if (original == null || draft.position != original.position) addProperty("position", notesPosition(draft.position))
        // Existing drafts retain every field. Hidden, untouched fields are omitted, not reset.
        if ((original == null && type == NotesType.DIARY) || (original != null && draft.content != original.content)) addProperty("content", draft.content)
        if ((original == null && type == NotesType.TASKS) || (original != null && draft.description != original.description)) addProperty("description", draft.description)
        if ((original == null && type == NotesType.TASKS && draft.endDate.isNotEmpty()) || (original != null && draft.endDate != original.endDate)) addProperty("endDate", draft.endDate)
        if ((original == null && type != NotesType.DIARY) || (original != null && draft.completed != original.completed)) addProperty("completed", draft.completed)
        if ((original == null && type == NotesType.DIARY) || (original != null && draft.style != original.style)) add("style", Gson().toJsonTree(draft.style))
    }
}

class NotesDataException : Exception()
object NotesDecoder {
    private fun JsonObject.string(key: String, default: String? = null): String {
        val value = get(key)
        if (value == null || value.isJsonNull) return default ?: throw NotesDataException()
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString
    }
    private fun JsonObject.number(key: String, default: Long? = null): Long {
        val value = get(key)
        if (value == null || value.isJsonNull) return default ?: throw NotesDataException()
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.asString.matches(Regex("0|[1-9][0-9]*")))
        return value.asString.toLong()
    }
    private fun JsonObject.boolean(key: String, default: Boolean = false): Boolean {
        val value = get(key)
        if (value == null || value.isJsonNull) return default
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
        return value.asBoolean
    }
    private fun JsonObject.array(key: String): List<JsonElement> = get(key)?.takeUnless { it.isJsonNull }?.asJsonArray?.toList() ?: emptyList()
    private inline fun <T> decode(block: () -> T): T = try { block() } catch (_: Exception) { throw NotesDataException() }
    fun item(json: JsonElement): NotesItem = decode {
        val obj = json.asJsonObject
        val style = obj.get("style")?.takeUnless { it.isJsonNull }?.asJsonObject ?: JsonObject()
        val endDate = obj.string("endDate", "").takeIf { it.isNotEmpty() }
        require(endDate == null || notesDateValid(endDate))
        NotesItem(obj.number("id").also { require(it > 0) }, obj.number("folderId").also { require(it > 0) },
            obj.number("position", 0).also { require(it <= Int.MAX_VALUE) }.toInt(), obj.string("title"), obj.string("content", ""),
            obj.string("description", ""), endDate, obj.boolean("completed"),
            NotesStyle(style.boolean("bold"), style.boolean("italic"), style.string("textColor", "#1f2937"),
                style.string("backgroundColor", "#ffffff"), style.number("fontSize", 16).also { require(it in 1..Int.MAX_VALUE) }.toInt()),
            OffsetDateTime.parse(obj.string("createdAt")), OffsetDateTime.parse(obj.string("updatedAt")))
    }
    fun folderId(json: JsonElement): Long = decode { json.asJsonObject.number("id").also { require(it > 0) } }
    fun tree(json: JsonElement): List<NotesFolder> = decode {
        val folderIds = mutableSetOf<Long>()
        val itemIds = mutableSetOf<Long>()
        fun parse(json: JsonElement, parent: Long?, depth: Int): NotesFolder {
            require(depth <= 100)
            val obj = json.asJsonObject
            val id = obj.number("id").also { require(it > 0 && folderIds.add(it)) }
            val actualParent = obj.get("parentId")?.takeUnless { it.isJsonNull }?.let { obj.number("parentId") }
            require(actualParent == parent)
            val notes = obj.array("notes").map(::item).onEach { require(it.folderId == id && itemIds.add(it.id)) }
            // Preserve server order, including all tie-breaks. Do not mix children and items.
            return NotesFolder(id, obj.string("name"), NotesType.parse(obj.string("type", "diary")),
                obj.number("position", 0).also { require(it <= Int.MAX_VALUE) }.toInt(), actualParent,
                obj.array("children").map { parse(it, id, depth + 1) }, notes)
        }
        json.asJsonArray.map { parse(it, null, 0) }
    }
}
