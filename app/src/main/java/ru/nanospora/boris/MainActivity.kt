package ru.nanospora.boris

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin

/** A small, dependency-free controller for Valetudo/Roborock HTTP panels. */
class MainActivity : AppCompatActivity() {
    private val worker = Executors.newFixedThreadPool(24)
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("boris", Context.MODE_PRIVATE) }
    private val client = VacuumClient()
    private var host: String? = null
    private var currentStatus: VacuumStatus? = null
    private lateinit var root: LinearLayout
    private val ubuntuMedium by lazy { resources.getFont(R.font.ubuntu_medium) }
    private val ubuntuBold by lazy { resources.getFont(R.font.ubuntu_bold) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.setDecorFitsSystemWindows(true)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        showConnecting("Ищу пылесос…")
        connectToVacuum()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun connectToVacuum() {
        val last = prefs.getString(KEY_LAST_HOST, null)
        if (last != null) {
            showConnecting("Подключаюсь к $last…")
            worker.execute {
                val found = client.status(last)
                if (found != null) {
                    main.post { attach(last, found) }
                } else {
                    scanNetwork()
                }
            }
        } else scanNetwork()
    }

    private fun scanNetwork() {
        main.post { showConnecting("Последний адрес недоступен. Ищу пылесос в этой сети…") }
        worker.execute {
            val prefix = localPrefix()
            if (prefix == null) {
                main.post { showConnectionError("Не удалось определить локальную сеть") }
                return@execute
            }
            val found = AtomicBoolean(false)
            val checked = CountDownLatch(254)
            for (n in 1..254) {
                val candidate = "$prefix.$n"
                worker.execute {
                    try {
                        if (!found.get()) {
                            val status = client.status(candidate)
                            if (status != null && found.compareAndSet(false, true)) {
                                main.post { attach(candidate, status) }
                            }
                        }
                    } finally {
                        checked.countDown()
                    }
                }
            }
            checked.await()
            if (!found.get()) main.post { showConnectionError("Пылесос не найден. Проверьте, что телефон подключён к той же Wi‑Fi сети.") }
        }
    }

    private fun attach(newHost: String, status: VacuumStatus) {
        host = newHost
        currentStatus = status
        prefs.edit().putString(KEY_LAST_HOST, newHost).apply()
        showDashboard()
    }

    private fun showConnecting(message: String) {
        root = vertical(pad = 28).apply {
            gravity = Gravity.CENTER
            addView(title("Борис"))
            addView(text(message, 18, MUTED).apply { setPadding(0, 18, 0, 22) })
            addView(ProgressBar(this@MainActivity))
        }
        setContentView(root)
    }

    private fun showConnectionError(message: String) {
        root = vertical(pad = 24).apply {
            gravity = Gravity.CENTER
            addView(title("Борис"))
            addView(text(message, 17, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, 20, 0, 24) })
            addView(actionButton("Повторить поиск") { connectToVacuum() })
            addView(actionButton("Указать адрес") { askForHost() }.apply { setPadding(0, 12, 0, 0) })
        }
        setContentView(root)
    }

    private fun showDashboard() {
        val content = vertical(pad = 16).apply { background = null }
        val scroller = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        root = vertical().apply {
            background = WaveBackgroundDrawable(themeColors())
            addView(header())
            addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(tabBar(content))
        }
        setContentView(root)
        applySystemInsets(root)
        showControls(content)
    }

    private fun header(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(6, 4, 6, 4)
        addView(text("Главная", 22, TEXT).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, 64))
    }

    private fun tabBar(content: LinearLayout): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(8, 10, 8, 18)
        addView(tabButton("Главная") { showControls(content) }, LinearLayout.LayoutParams(0, 150, 1f).apply { setMargins(0, 0, 4, 0) })
        addView(tabButton("Расходники") { showConsumables(content) }, LinearLayout.LayoutParams(0, 150, 1f).apply { setMargins(4, 0, 4, 0) })
        addView(tabButton("В браузер") { openPanel() }, LinearLayout.LayoutParams(0, 150, 1f).apply { setMargins(4, 0, 4, 0) })
        addView(tabButton("Настройки") { showSettings(content) }, LinearLayout.LayoutParams(0, 150, 1f).apply { setMargins(4, 0, 0, 0) })
    }

    private fun showSettings(content: LinearLayout, devices: List<Pair<String, VacuumStatus>> = emptyList(), networkDevices: List<Pair<String, String>> = emptyList()) {
        content.removeAllViews()
        content.gravity = Gravity.NO_GRAVITY
        content.addView(section("Настройки"))
        content.addView(text("Подключён: ${host ?: "не найден"}", 16, MUTED).apply { setPadding(4, 0, 4, 12) })
        content.addView(settingButton("Тема: ${themeName()}") { chooseTheme(content) })
        content.addView(settingButton("Переподключить пылесос") { connectToVacuum() })
        content.addView(settingButton("Найти пылесосы в сети") { findDevicesInNetwork(content) })
        content.addView(settingButton("Сканировать все устройства сети") { scanNetworkDevices(content) })
        content.addView(settingButton("Ввести адрес вручную") { askForHost() })
        content.addView(settingButton("Проверить связь") { refreshStatus() })
        if (devices.isNotEmpty()) {
            content.addView(section("Найденные устройства"))
            devices.forEach { (deviceHost, status) ->
                content.addView(settingButton("$deviceHost · ${status.stateText} · ${status.battery ?: 0}%") {
                    attach(deviceHost, status)
                })
            }
        }
        if (networkDevices.isNotEmpty()) {
            content.addView(section("Устройства в сети"))
            networkDevices.forEach { (deviceHost, name) ->
                content.addView(settingButton("$name\n$deviceHost") { askForHost(deviceHost) })
            }
        }
    }

    private fun settingButton(label: String, click: () -> Unit) = glassButton(label, SURFACE_RAISED, 17, click).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = 176
        setPadding(22, 0, 18, 0)
        layoutParams = LinearLayout.LayoutParams(-1, 176).apply { setMargins(0, 0, 0, 16) }
    }

    private fun chooseTheme(content: LinearLayout) {
        val items = arrayOf("Синие волны", "Изумрудные волны", "Фиолетовые волны")
        AlertDialog.Builder(this)
            .setTitle("Тема")
            .setSingleChoiceItems(items, prefs.getInt(KEY_THEME, 0)) { dialog, which ->
                prefs.edit().putInt(KEY_THEME, which).apply()
                dialog.dismiss()
                showDashboard()
                root.post { showSettings((root.getChildAt(1) as ScrollView).getChildAt(0) as LinearLayout) }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun findDevicesInNetwork(content: LinearLayout) {
        content.removeAllViews()
        content.addView(section("Поиск в сети"))
        content.addView(text("Проверяю устройства в текущей Wi‑Fi сети…", 16, MUTED))
        worker.execute {
            val prefix = localPrefix()
            if (prefix == null) {
                main.post { showSettings(content) }
                return@execute
            }
            val devices = java.util.Collections.synchronizedList(mutableListOf<Pair<String, VacuumStatus>>())
            val checked = CountDownLatch(254)
            for (n in 1..254) {
                val candidate = "$prefix.$n"
                worker.execute {
                    try { client.status(candidate)?.let { devices.add(candidate to it) } }
                    finally { checked.countDown() }
                }
            }
            checked.await()
            main.post {
                showSettings(content, devices.sortedBy { it.first })
                if (devices.isEmpty()) toast("Совместимые пылесосы не найдены")
            }
        }
    }

    private fun scanNetworkDevices(content: LinearLayout) {
        content.removeAllViews()
        content.addView(section("Сканирование сети"))
        content.addView(text("Ищу доступные устройства. Это может занять около минуты…", 16, MUTED))
        worker.execute {
            val prefix = localPrefix()
            if (prefix == null) {
                main.post { showSettings(content) }
                return@execute
            }
            val devices = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
            val checked = CountDownLatch(254)
            for (n in 1..254) {
                val candidate = "$prefix.$n"
                worker.execute {
                    try {
                        val address = InetAddress.getByName(candidate)
                        if (isVisibleInNetwork(address)) {
                            val vacuum = client.status(candidate)
                            val resolvedName = runCatching { address.canonicalHostName }.getOrDefault(candidate)
                            val label = when {
                                vacuum != null -> "Roborock · ${vacuum.stateText} · ${vacuum.battery ?: 0}%"
                                resolvedName != candidate -> resolvedName
                                else -> "Устройство в сети"
                            }
                            devices.add(candidate to label)
                        }
                    } catch (_: Exception) {
                    } finally {
                        checked.countDown()
                    }
                }
            }
            checked.await()
            main.post {
                showSettings(content, networkDevices = devices.sortedBy { it.first })
                if (devices.isEmpty()) toast("Доступные устройства не найдены")
            }
        }
    }

    private fun isVisibleInNetwork(address: InetAddress): Boolean {
        if (runCatching { address.isReachable(350) }.getOrDefault(false)) return true
        return intArrayOf(80, 443, 22, 554, 8080).any { port ->
            runCatching {
                Socket().use { socket -> socket.connect(InetSocketAddress(address, port), 280) }
                true
            }.getOrDefault(false)
        }
    }

    private fun showControls(content: LinearLayout) {
        content.removeAllViews()
        content.gravity = Gravity.CENTER_VERTICAL
        val status = currentStatus
        content.addView(statusCard(status))
        content.addView(View(this).apply { setBackgroundColor(Color.rgb(76, 84, 87)) }, LinearLayout.LayoutParams(-1, 1).apply { setMargins(0, 12, 0, 14) })
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        grid.addView(commandRow("▶  Старт", "Ⅱ  Пауза", { command("api/start_cleaning", "Уборка начата") }, { command("api/pause_cleaning", "Пауза") }, GREEN, AMBER))
        grid.addView(commandRow("■  Стоп", "⌂  Домой", { command("api/stop_cleaning", "Уборка остановлена") }, { command("api/drive_home", "Робот едет к базе") }, RED, BLUE))
        grid.addView(commandButton("⌕  Найти робота", PURPLE) { command("api/find_robot", "Команда поиска отправлена") }, LinearLayout.LayoutParams(-1, 225).apply { setMargins(0, 0, 0, 16) })
        content.addView(grid)
        content.addView(powerPicker())
        content.addView(batteryCard(status))
    }

    private fun statusCard(status: VacuumStatus?): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(12, 18, 12, 4)
        addView(text(status?.stateText ?: "Нет данных", 23, TEXT).apply { gravity = Gravity.CENTER })
        addView(text("Пылесос подключён", 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, 10, 0, 0) })
    }

    private fun showConsumables(content: LinearLayout) {
        content.removeAllViews()
        content.gravity = Gravity.NO_GRAVITY
        content.addView(section("Расходные компоненты"))
        content.addView(text("Загружаю данные пылесоса…", 16, MUTED))
        val activeHost = host ?: return
        worker.execute {
            val data = client.consumables(activeHost)
            main.post {
                content.removeAllViews()
                content.addView(section("Расходные компоненты"))
                if (data == null) {
                    content.addView(text("Пылесос не отдал данные о расходниках. Откройте раздел «Расходные компоненты» в штатной панели.", 16, MUTED))
                    content.addView(actionButton("Открыть штатную панель") { openPanel() }.apply { setPadding(0, 18, 0, 0) })
                } else {
                    consumableRows(data, content).forEach { content.addView(it) }
                    val summary = data.optJSONArray("summary")
                    if (summary != null) {
                        content.addView(section("Статистика за всё время"))
                        content.addView(text("Убранная площадь: ${"%.1f".format(summary.optLong(1) / 1_000_000.0)} м²", 22, TEXT))
                        content.addView(text("Общее время уборок: ${"%.1f".format(summary.optLong(0) / 3600.0)} ч", 22, TEXT).apply { setPadding(0, 14, 0, 0) })
                        content.addView(text("Количество уборок: ${summary.optInt(2)}", 22, TEXT).apply { setPadding(0, 14, 0, 0) })
                    }
                }
            }
        }
    }

    private fun consumableRows(json: JSONObject, content: LinearLayout): List<View> {
        val counters = json.optJSONObject("consumables") ?: json
        return listOf(
            Triple("Главная щётка", counters.optLong("main_brush_work_time", 0), 300L),
            Triple("Боковая щётка", counters.optLong("side_brush_work_time", 0), 200L),
            Triple("Пылефильтр", counters.optLong("filter_work_time", 0), 150L),
            Triple("Чистка сенсоров", counters.optLong("sensor_dirty_time", 0), 30L)
        ).map { (name, seconds, resourceHours) ->
            val remaining = (resourceHours - seconds / 3600.0).coerceAtLeast(0.0)
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = glass(SURFACE, 175, 16)
                setPadding(22, 20, 16, 20)
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(name, 28, TEXT))
                    addView(text("Осталось ${"%.1f".format(remaining)} ч", 24, ACCENT).apply { setPadding(0, 9, 0, 0) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(glassButton("Сбросить", RED, 14) { resetConsumable(name, secondsKey(name)) { showConsumables(content) } }, LinearLayout.LayoutParams(200, 86))
            }.apply {
                layoutParams = LinearLayout.LayoutParams(-1, 230).apply { setMargins(0, 0, 0, 20) }
            }
        }
    }

    private fun secondsKey(name: String) = when (name) {
        "Главная щётка" -> "main_brush_work_time"
        "Боковая щётка" -> "side_brush_work_time"
        "Пылефильтр" -> "filter_work_time"
        else -> "sensor_dirty_time"
    }

    private fun resetConsumable(name: String, key: String, onDone: () -> Unit) {
        val activeHost = host ?: return
        AlertDialog.Builder(this)
            .setTitle("Сбросить счётчик?")
            .setMessage("Счётчик «$name» будет сброшен на пылесосе.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сбросить") { _, _ ->
                worker.execute {
                    val ok = client.put(activeHost, "api/reset_consumable", JSONObject().put("consumable", key).toString())
                    main.post {
                        toast(if (ok) "Счётчик сброшен" else "Не удалось сбросить счётчик")
                        if (ok) onDone()
                    }
                }
            }
            .show()
    }

    private fun refreshStatus() {
        val activeHost = host ?: return
        worker.execute {
            val status = client.status(activeHost)
            main.post {
                if (status != null) { currentStatus = status; showDashboard() }
                else showConnectionError("Связь с пылесосом потеряна")
            }
        }
    }

    private fun command(path: String, success: String) {
        val activeHost = host ?: return
        worker.execute {
            val ok = client.put(activeHost, path)
            main.post { toast(if (ok) success else "Команда не выполнена"); refreshStatus() }
        }
    }

    private fun setFanSpeed(speed: Int) {
        val activeHost = host ?: return
        worker.execute {
            val ok = client.put(activeHost, "api/fanspeed", JSONObject().put("speed", speed).toString())
            main.post { toast(if (ok) "Мощность изменена" else "Не удалось изменить мощность") }
        }
    }

    private fun powerPicker(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(12, 18, 12, 18)
        addView(text("Мощность пылесоса", 18, MUTED), LinearLayout.LayoutParams(0, -2, 1f))
        val choices = listOf("Тихий" to 38, "Обычный" to 60, "Макс" to 100)
        addView(Spinner(this@MainActivity).apply {
            adapter = object : android.widget.ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, choices.map { it.first }) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    (super.getView(position, convertView, parent) as TextView).apply {
                        typeface = ubuntuMedium
                        setTextColor(TEXT)
                        gravity = Gravity.CENTER
                        setPadding(8, 0, 8, 0)
                    }
                override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    (super.getDropDownView(position, convertView, parent) as TextView).apply {
                        typeface = ubuntuMedium
                        setTextColor(Color.WHITE)
                    }
            }
            setSelection(2)
            setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                private var initial = true
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (initial) { initial = false; return }
                    setFanSpeed(choices[position].second)
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            })
            background = glass(BLUE_DARK, 205, 18)
            setPadding(8, 0, 8, 0)
        }, LinearLayout.LayoutParams(270, 124))
    }

    private fun batteryCard(status: VacuumStatus?): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = glass(SURFACE_RAISED, 175, 20)
        setPadding(20, 16, 20, 18)
        val level = status?.battery ?: 0
        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text("Батарея", 18, TEXT), LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("$level%", 27, GREEN))
        })
        addView(text(if (status?.stateText == "Заряжается") "Заряжается" else "Уровень заряда", 14, MUTED).apply { setPadding(0, 2, 0, 12) })
        addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = level
            progressTintList = android.content.res.ColorStateList.valueOf(if (level > 20) GREEN else RED)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(55, 64, 65))
        }, LinearLayout.LayoutParams(-1, 12))
    }

    private fun mapSlotDialog(store: Boolean) {
        val input = EditText(this).apply { hint = "Название карты" }
        AlertDialog.Builder(this)
            .setTitle(if (store) "Сохранить карту" else "Загрузить карту")
            .setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton(if (store) "Сохранить" else "Загрузить") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) { toast("Введите название карты"); return@setPositiveButton }
                val activeHost = host ?: return@setPositiveButton
                worker.execute {
                    val path = if (store) "api/store_map" else "api/load_map"
                    val ok = client.put(activeHost, path, JSONObject().put("name", name).toString())
                    main.post { toast(if (ok) "Команда отправлена" else "Не удалось выполнить команду") }
                }
            }.show()
    }

    private fun goToDialog() = coordinateDialog("Переход к точке", "X", "Y") { x, y ->
        val activeHost = host ?: return@coordinateDialog
        worker.execute {
            val ok = client.put(activeHost, "api/go_to", JSONObject().put("x", x).put("y", y).toString())
            main.post { toast(if (ok) "Точка отправлена" else "Не удалось отправить точку") }
        }
    }

    private fun zoneDialog() = coordinateDialog("Зональная уборка", "Левый верхний X", "Левый верхний Y") { x1, y1 ->
        coordinateDialog("Зональная уборка", "Правый нижний X", "Правый нижний Y") { x2, y2 ->
            val activeHost = host ?: return@coordinateDialog
            val zone = org.json.JSONArray().put(x1).put(y1).put(x2).put(y2).put(1)
            val body = org.json.JSONArray().put(zone).toString()
            worker.execute {
                val ok = client.put(activeHost, "api/start_cleaning_zone", body)
                main.post { toast(if (ok) "Зональная уборка запущена" else "Не удалось запустить уборку зоны") }
            }
        }
    }

    private fun coordinateDialog(title: String, xHint: String, yHint: String, onDone: (Int, Int) -> Unit) {
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 0, 48, 0) }
        val x = EditText(this).apply { hint = xHint; inputType = 2 }
        val y = EditText(this).apply { hint = yHint; inputType = 2 }
        holder.addView(x); holder.addView(y)
        AlertDialog.Builder(this).setTitle(title).setView(holder)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Отправить") { _, _ ->
                val xv = x.text.toString().toIntOrNull(); val yv = y.text.toString().toIntOrNull()
                if (xv == null || yv == null) toast("Нужны целые координаты") else onDone(xv, yv)
            }.show()
    }

    private fun askForHost(initialAddress: String = "") {
        val input = EditText(this).apply { hint = "Например, 192.168.1.132"; setText(initialAddress) }
        AlertDialog.Builder(this).setTitle("Адрес пылесоса").setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Подключиться") { _, _ ->
                val candidate = input.text.toString().trim().removePrefix("http://").substringBefore('/')
                if (candidate.isNotBlank()) {
                    showConnecting("Подключаюсь к $candidate…")
                    worker.execute { client.status(candidate)?.let { main.post { attach(candidate, it) } } ?: main.post { showConnectionError("Пылесос по адресу $candidate не ответил") } }
                }
            }.show()
    }

    private fun openPanel() {
        val activeHost = host ?: return
        AlertDialog.Builder(this)
            .setTitle("Открыть штатную панель?")
            .setMessage("Она откроется в браузере: $activeHost")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Открыть") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://$activeHost/#home.html")))
            }
            .show()
    }

    private fun localPrefix(): String? {
        val manager = getSystemService(ConnectivityManager::class.java)
        val addresses = manager.allNetworks
            .sortedByDescending { network -> manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            .flatMap { network -> manager.getLinkProperties(network)?.linkAddresses.orEmpty() }
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
        return addresses.firstOrNull()?.hostAddress?.substringBeforeLast('.')
    }

    private fun commandRow(left: String, right: String, onLeft: () -> Unit, onRight: () -> Unit, leftColor: Int, rightColor: Int): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(commandButton(left, leftColor, onLeft), LinearLayout.LayoutParams(0, 225, 1f).apply { setMargins(0, 0, 8, 16) })
            addView(commandButton(right, rightColor, onRight), LinearLayout.LayoutParams(0, 225, 1f).apply { setMargins(8, 0, 0, 16) })
        }

    private fun actionButton(label: String, click: () -> Unit) = glassButton(label, BLUE, 17, click)
    private fun commandButton(label: String, color: Int, click: () -> Unit) = glassButton(label, color, 19, click)
    private fun smallButton(label: String, click: () -> Unit) = glassButton(label, SURFACE_RAISED, 22, click)
    private fun tabButton(label: String, click: () -> Unit) = glassButton(label, SURFACE_RAISED, 14, click)
    private fun glassButton(label: String, color: Int, size: Int, click: () -> Unit) = TextView(this).apply {
        text = label
        textSize = size.toFloat()
        typeface = ubuntuMedium
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(TEXT)
        background = glass(color, 205, 18)
        setPadding(10, 0, 10, 0)
        setOnClickListener { animateTap(this, click) }
    }
    private fun section(value: String) = text(value, 14, ACCENT).apply { setPadding(4, 22, 4, 10) }
    private fun title(value: String) = text(value, 32, TEXT).apply { gravity = Gravity.CENTER; typeface = ubuntuBold }
    private fun text(value: String, size: Int, color: Int) = TextView(this).apply { text = value; textSize = size.toFloat(); typeface = ubuntuMedium; setTextColor(color) }
    private fun vertical(pad: Int = 0) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = screenGradient(); setPadding(pad, pad, pad, pad) }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius * resources.displayMetrics.density }
    private fun glass(color: Int, alpha: Int, radius: Int) = GradientDrawable().apply {
        colors = intArrayOf(
            Color.argb(alpha, (Color.red(color) + 255) / 2, (Color.green(color) + 255) / 2, (Color.blue(color) + 255) / 2),
            Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        )
        orientation = GradientDrawable.Orientation.TL_BR
        cornerRadius = radius * resources.displayMetrics.density
    }
    private fun screenGradient() = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        themeColors()
    )
    private fun themeColors() = when (prefs.getInt(KEY_THEME, 0)) {
        1 -> intArrayOf(Color.rgb(20, 67, 64), Color.rgb(7, 35, 39), Color.rgb(4, 20, 27))
        2 -> intArrayOf(Color.rgb(51, 37, 83), Color.rgb(26, 20, 53), Color.rgb(11, 10, 28))
        else -> intArrayOf(Color.rgb(18, 42, 62), Color.rgb(9, 20, 35), Color.rgb(5, 13, 24))
    }
    private fun themeName() = when (prefs.getInt(KEY_THEME, 0)) {
        1 -> "изумрудные волны"
        2 -> "фиолетовые волны"
        else -> "синие волны"
    }
    private fun toast(message: String) = android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    private fun animateTap(view: View, action: () -> Unit) {
        view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).withEndAction {
            action()
            view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()
    }
    private fun applySystemInsets(view: View) {
        view.setOnApplyWindowInsetsListener { target, insets ->
            target.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        view.requestApplyInsets()
    }

    companion object {
        private const val KEY_LAST_HOST = "last_host"
        private const val KEY_THEME = "theme"
        private val BG = Color.rgb(11, 24, 37)
        private val SURFACE = Color.rgb(29, 50, 68)
        private val SURFACE_RAISED = Color.rgb(42, 77, 108)
        private val BLUE = Color.rgb(13, 105, 216)
        private val BLUE_DARK = Color.rgb(30, 65, 103)
        private val GREEN = Color.rgb(39, 174, 96)
        private val AMBER = Color.rgb(240, 169, 50)
        private val RED = Color.rgb(211, 74, 74)
        private val PURPLE = Color.rgb(115, 88, 190)
        private val ACCENT = Color.rgb(255, 171, 64)
        private val TEXT = Color.rgb(244, 244, 237)
        private val MUTED = Color.rgb(179, 188, 183)
        private val DANGER = Color.rgb(229, 115, 115)
    }
}

/** Slow translucent sine waves, painted behind the controls. */
private class WaveBackgroundDrawable(private val colors: IntArray) : Drawable() {
    private val base = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private var phase = 0f
    private var running = false

    override fun draw(canvas: Canvas) {
        base.bounds = bounds
        base.draw(canvas)
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        drawWave(canvas, width, height, 0.28f, 52f, 0.009f, phase, Color.argb(48, 178, 223, 255))
        drawWave(canvas, width, height, 0.62f, 66f, 0.007f, phase * 0.7f, Color.argb(40, 211, 233, 255))
        if (!running) {
            running = true
            scheduleSelf(tick, System.currentTimeMillis() + 32)
        }
    }

    private fun drawWave(canvas: Canvas, width: Float, height: Float, y: Float, amplitude: Float, frequency: Float, offset: Float, color: Int) {
        path.reset()
        path.moveTo(0f, height)
        for (x in 0..width.toInt() step 8) {
            val waveY = height * y + sin(x * frequency + offset) * amplitude
            path.lineTo(x.toFloat(), waveY)
        }
        path.lineTo(width, height)
        path.close()
        paint.color = color
        canvas.drawPath(path, paint)
    }

    private val tick = object : Runnable {
        override fun run() {
            phase -= 0.052f
            invalidateSelf()
            scheduleSelf(this, System.currentTimeMillis() + 32)
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
}

private data class VacuumStatus(val battery: Int?, val stateText: String)

private class VacuumClient {
    fun status(host: String): VacuumStatus? {
        return try {
            val response = request(host, "api/current_status", "GET") ?: return null
            val json = JSONObject(response)
            if (!json.has("state") && !json.has("battery")) return null
            val state = json.optInt("state", -1)
            VacuumStatus(json.optInt("battery", -1).takeIf { it >= 0 }, when (state) {
                8 -> "Заряжается"; 5, 11, 16, 17, 18 -> "Убирается"; 6, 10 -> "На паузе"; 12 -> "Возвращается на базу"; else -> "Готов к работе"
            })
        } catch (_: Exception) { null }
    }

    fun consumables(host: String): JSONObject? {
        return try {
            val response = request(host, "api/consumable_status", "GET") ?: return null
            JSONObject(response)
        } catch (_: Exception) { null }
    }
    fun put(host: String, path: String, body: String? = null): Boolean = try { request(host, path, "PUT", body) != null } catch (_: Exception) { false }

    private fun request(host: String, path: String, method: String, body: String? = null): String? {
        val url = URL("http://$host/${path.removePrefix("/")}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 900; readTimeout = 1_500; useCaches = false
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json; charset=UTF-8") }
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray()) }
            if (connection.responseCode !in 200..399) null else connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
}
