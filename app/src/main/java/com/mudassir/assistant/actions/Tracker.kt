package com.mudassir.assistant.actions

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Expenses, stock holdings, saved places and place reminders. Stored on the phone. */
object Tracker {

    data class Expense(val amount: Double, val category: String, val time: Long)
    data class Holding(val symbol: String, val shares: Double, val avgPrice: Double)
    data class Place(val name: String, val lat: Double, val lng: Double)
    data class PlaceReminder(val id: String, val place: String, val text: String)

    private lateinit var file: File
    private val _expenses = MutableStateFlow<List<Expense>>(emptyList())
    val expenses: StateFlow<List<Expense>> = _expenses.asStateFlow()
    private val _holdings = MutableStateFlow<List<Holding>>(emptyList())
    val holdings: StateFlow<List<Holding>> = _holdings.asStateFlow()
    private val _places = MutableStateFlow<List<Place>>(emptyList())
    val places: StateFlow<List<Place>> = _places.asStateFlow()
    private val _placeReminders = MutableStateFlow<List<PlaceReminder>>(emptyList())
    val placeReminders: StateFlow<List<PlaceReminder>> = _placeReminders.asStateFlow()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "tracker.json")
        runCatching {
            val o = JSONObject(file.readText())
            _expenses.value = o.optJSONArray("expenses").objects().map { Expense(it.getDouble("a"), it.getString("c"), it.getLong("t")) }
            _holdings.value = o.optJSONArray("holdings").objects().map { Holding(it.getString("s"), it.getDouble("n"), it.getDouble("p")) }
            _places.value = o.optJSONArray("places").objects().map { Place(it.getString("n"), it.getDouble("lat"), it.getDouble("lng")) }
            _placeReminders.value = o.optJSONArray("placeReminders").objects().map { PlaceReminder(it.getString("id"), it.getString("p"), it.getString("t")) }
        }
    }

    // ---- expenses ----

    fun addExpense(amount: Double, category: String) {
        _expenses.value = _expenses.value + Expense(amount, category.trim().lowercase(), System.currentTimeMillis())
        save()
    }

    fun removeExpense(e: Expense) {
        _expenses.value = _expenses.value - e
        save()
    }

    /** Expenses in a range ("today", "yesterday", "this week", "this month", "last month", "all"). */
    fun expensesIn(range: String, category: String? = null): List<Expense> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val (from, to) = when (range) {
            "today" -> today to today.plusDays(1)
            "yesterday" -> today.minusDays(1) to today
            "this week" -> today.minusDays((today.dayOfWeek.value - 1).toLong()) to today.plusDays(1)
            "last month" -> today.withDayOfMonth(1).minusMonths(1) to today.withDayOfMonth(1)
            "all" -> LocalDate.of(2000, 1, 1) to today.plusDays(1)
            else -> today.withDayOfMonth(1) to today.plusDays(1)
        }
        return _expenses.value.filter {
            val d = Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate()
            !d.isBefore(from) && d.isBefore(to) && (category == null || matches(it.category, category))
        }
    }

    /** "fuel" finds "petrol" too; otherwise a loose match on the words. */
    private fun matches(have: String, want: String): Boolean {
        val w = want.lowercase().trim()
        val groups = listOf(setOf("petrol", "fuel", "diesel", "gas station", "cng"), setOf("food", "lunch", "dinner", "breakfast", "restaurant", "khana"),
            setOf("grocery", "groceries", "sauda", "shopping"), setOf("bill", "bills", "electricity", "gas bill", "internet"))
        if (groups.any { g -> g.any { have.contains(it) } && g.any { w.contains(it) } }) return true
        return have.contains(w) || w.contains(have)
    }

    // ---- stocks ----

    fun buy(symbol: String, shares: Double, price: Double) {
        val old = _holdings.value.firstOrNull { it.symbol == symbol }
        val updated = if (old == null) Holding(symbol, shares, price)
        else Holding(symbol, old.shares + shares, (old.shares * old.avgPrice + shares * price) / (old.shares + shares))
        _holdings.value = _holdings.value.filterNot { it.symbol == symbol } + updated
        save()
    }

    /** Returns the shares left, or null if you didn't hold it. */
    fun sell(symbol: String, shares: Double): Double? {
        val old = _holdings.value.firstOrNull { it.symbol == symbol } ?: return null
        val left = (old.shares - shares).coerceAtLeast(0.0)
        _holdings.value = _holdings.value.filterNot { it.symbol == symbol } + listOfNotNull(old.copy(shares = left).takeIf { left > 0 })
        save()
        return left
    }

    fun removeHolding(h: Holding) {
        _holdings.value = _holdings.value - h
        save()
    }

    // ---- places ----

    fun savePlace(name: String, lat: Double, lng: Double) {
        _places.value = _places.value.filterNot { it.name == name } + Place(name, lat, lng)
        save()
    }

    fun place(name: String) = _places.value.firstOrNull { it.name.equals(name, true) }

    fun removePlace(p: Place) {
        _places.value = _places.value - p
        save()
    }

    fun addPlaceReminder(r: PlaceReminder) {
        _placeReminders.value = _placeReminders.value + r
        save()
    }

    fun removePlaceReminder(id: String) {
        _placeReminders.value = _placeReminders.value.filterNot { it.id == id }
        save()
    }

    // ---- storage ----

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun save() {
        val o = JSONObject()
            .put("expenses", JSONArray(_expenses.value.map { JSONObject().put("a", it.amount).put("c", it.category).put("t", it.time) }))
            .put("holdings", JSONArray(_holdings.value.map { JSONObject().put("s", it.symbol).put("n", it.shares).put("p", it.avgPrice) }))
            .put("places", JSONArray(_places.value.map { JSONObject().put("n", it.name).put("lat", it.lat).put("lng", it.lng) }))
            .put("placeReminders", JSONArray(_placeReminders.value.map { JSONObject().put("id", it.id).put("p", it.place).put("t", it.text) }))
        val tmp = File(file.parentFile, "tracker.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }
}
