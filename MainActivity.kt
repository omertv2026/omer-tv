package com.omertv.player

import android.content.ClipDescription
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.DragEvent
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

data class ChannelItem(
    val id: String,
    val name: String,
    val url: String,
    val group: String
)

data class SourceEntry(
    val id: String,
    val type: String,
    val field1: String,
    val field2: String,
    val field3: String,
    val rawM3u: String
)

class MainActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var channelSidebar: LinearLayout
    private lateinit var videoPane: FrameLayout
    private lateinit var settingsScroll: ScrollView
    private lateinit var rvChannels: RecyclerView
    private lateinit var rvSources: RecyclerView
    private lateinit var tvVolumeOverlay: TextView
    private lateinit var tvActiveSourceInfo: TextView

    private lateinit var spSourceType: Spinner
    private lateinit var etTitleOrUrl: EditText
    private lateinit var etExtra1: EditText
    private lateinit var etExtra2: EditText
    private lateinit var etRawPlaylist: EditText

    private val allChannels = mutableListOf<ChannelItem>()
    private val visibleChannels = mutableListOf<ChannelItem>()
    private val favoriteIdsInOrder = mutableListOf<String>()
    private val sources = mutableListOf<SourceEntry>()

    private var currentTab = "CANLI"
    private var isFullscreen = false
    private var editingSourceId: String? = null
    private var lastMouseY: Float? = null
    private var numberBuffer = ""
    private val numberHandler = Handler(Looper.getMainLooper())
    private val volumeHideHandler = Handler(Looper.getMainLooper())

    private val prefs by lazy { getSharedPreferences("omer_tv_prefs", Context.MODE_PRIVATE) }
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var sourceAdapter: SourceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        player = ExoPlayer.Builder(this).build()
        playerView = findViewById(R.id.playerView)
        playerView.player = player

        channelSidebar = findViewById(R.id.channelSidebar)
        videoPane = findViewById(R.id.videoPane)
        settingsScroll = findViewById(R.id.settingsScroll)
        rvChannels = findViewById(R.id.rvChannels)
        rvSources = findViewById(R.id.rvSources)
        tvVolumeOverlay = findViewById(R.id.tvVolumeOverlay)
        tvActiveSourceInfo = findViewById(R.id.tvActiveSourceInfo)

        spSourceType = findViewById(R.id.spSourceType)
        etTitleOrUrl = findViewById(R.id.etTitleOrUrl)
        etExtra1 = findViewById(R.id.etExtra1)
        etExtra2 = findViewById(R.id.etExtra2)
        etRawPlaylist = findViewById(R.id.etRawPlaylist)

        setupTopBar()
        setupSettingsUi()
        setupLists()
        setupMouseVolumeControl()
        loadSavedState()
        rebuildChannelsFromSources()
        refreshVisibleChannels()
    }

    private fun setupTopBar() {
        findViewById<Button>(R.id.btnCanli).setOnClickListener {
            showPlayerMode()
            currentTab = "CANLI"
            refreshVisibleChannels()
        }
        findViewById<Button>(R.id.btnFilm).setOnClickListener {
            showPlayerMode()
            currentTab = "FILM"
            refreshVisibleChannels()
        }
        findViewById<Button>(R.id.btnDizi).setOnClickListener {
            showPlayerMode()
            currentTab = "DIZI"
            refreshVisibleChannels()
        }
        findViewById<Button>(R.id.btnFavori).setOnClickListener {
            showPlayerMode()
            currentTab = "FAVORILER"
            refreshVisibleChannels()
        }
        findViewById<Button>(R.id.btnAyarlar).setOnClickListener {
            showSettingsMode()
        }
    }

    private fun showPlayerMode() {
        settingsScroll.visibility = View.GONE
        findViewById<View>(R.id.playerContainer).visibility = View.VISIBLE
        setFullscreen(false)
    }

    private fun showSettingsMode() {
        findViewById<View>(R.id.playerContainer).visibility = View.GONE
        settingsScroll.visibility = View.VISIBLE
    }

    private fun setFullscreen(fullscreen: Boolean) {
        isFullscreen = fullscreen
        channelSidebar.visibility = if (fullscreen) View.GONE else View.VISIBLE
        findViewById<View>(R.id.topBar).visibility = if (fullscreen) View.GONE else View.VISIBLE
    }

    private fun setupSettingsUi() {
        val types = listOf("M3U", "XTREAM", "MAG", "TEK KANAL")
        spSourceType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)

        etRawPlaylist.setOnDragListener { v, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED ->
                    event.clipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
                DragEvent.ACTION_DROP -> {
                    val text = event.clipData?.getItemAt(0)?.coerceToText(this)?.toString()
                    if (!text.isNullOrBlank()) {
                        (v as EditText).setText(text)
                        true
                    } else false
                }
                else -> true
            }
        }

        findViewById<Button>(R.id.btnSaveSource).setOnClickListener {
            val type = spSourceType.selectedItem.toString()
            val f1 = etTitleOrUrl.text.toString().trim()
            val f2 = etExtra1.text.toString().trim()
            val f3 = etExtra2.text.toString().trim()
            val raw = etRawPlaylist.text.toString().trim()

            if (f1.isEmpty() && raw.isEmpty()) {
                Toast.makeText(this, "Lütfen bilgi veya liste girin", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val id = editingSourceId ?: System.currentTimeMillis().toString()
            val entry = SourceEntry(id, type, f1, f2, f3, raw)
            val idx = sources.indexOfFirst { it.id == id }
            if (idx >= 0) sources[idx] = entry else sources.add(entry)

            editingSourceId = null
            clearSourceInputs()
            saveSources()
            sourceAdapter.notifyDataSetChanged()
            rebuildChannelsFromSources()
            refreshVisibleChannels()
            Toast.makeText(this, "Kaydedildi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearSourceInputs() {
        etTitleOrUrl.setText("")
        etExtra1.setText("")
        etExtra2.setText("")
        etRawPlaylist.setText("")
    }

    private fun setupLists() {
        rvChannels.layoutManager = LinearLayoutManager(this)
        channelAdapter = ChannelAdapter(
            items = visibleChannels,
            isFavorite = { ch -> favoriteIdsInOrder.contains(ch.id) },
            onPlay = { ch -> playChannel(ch) },
            onToggleFav = { ch -> toggleFavorite(ch) },
            onDelete = { ch -> deleteChannel(ch) }
        )
        rvChannels.adapter = channelAdapter

        rvSources.layoutManager = LinearLayoutManager(this)
        sourceAdapter = SourceAdapter(
            items = sources,
            onEdit = { src ->
                editingSourceId = src.id
                val pos = when (src.type) {
                    "M3U" -> 0
                    "XTREAM" -> 1
                    "MAG" -> 2
                    else -> 3
                }
                spSourceType.setSelection(pos)
                etTitleOrUrl.setText(src.field1)
                etExtra1.setText(src.field2)
                etExtra2.setText(src.field3)
                etRawPlaylist.setText(src.rawM3u)
            },
            onDelete = { src ->
                sources.removeAll { it.id == src.id }
                saveSources()
                sourceAdapter.notifyDataSetChanged()
                rebuildChannelsFromSources()
                refreshVisibleChannels()
            }
        )
        rvSources.adapter = sourceAdapter
    }

    private fun setupMouseVolumeControl() {
        val hoverListener = View.OnHoverListener { _, event ->
            if (event.action == MotionEvent.ACTION_HOVER_MOVE) {
                val prevY = lastMouseY
                lastMouseY = event.y
                if (prevY != null) {
                    val deltaY = prevY - event.y
                    if (kotlin.math.abs(deltaY) > 12f) {
                        adjustVolume(deltaY > 0)
                    }
                }
            }
            false
        }
        videoPane.setOnHoverListener(hoverListener)
        rvChannels.setOnHoverListener(hoverListener)
    }

    private fun adjustVolume(up: Boolean) {
        val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val pct = (current * 100) / max
        tvVolumeOverlay.text = "Ses: %$pct"
        tvVolumeOverlay.visibility = View.VISIBLE
        volumeHideHandler.removeCallbacksAndMessages(null)
        volumeHideHandler.postDelayed({ tvVolumeOverlay.visibility = View.GONE }, 1300)
    }

    private fun toggleFavorite(channel: ChannelItem) {
        if (favoriteIdsInOrder.contains(channel.id)) {
            favoriteIdsInOrder.remove(channel.id)
        } else {
            favoriteIdsInOrder.add(channel.id)
        }
        saveFavorites()
        refreshVisibleChannels()
    }

    private fun deleteChannel(channel: ChannelItem) {
        favoriteIdsInOrder.remove(channel.id)
        allChannels.removeAll { it.id == channel.id }
        saveFavorites()
        refreshVisibleChannels()
    }

    private fun rebuildChannelsFromSources() {
        allChannels.clear()
        for (src in sources) {
            when (src.type) {
                "TEK KANAL" -> {
                    val name = if (src.field1.isNotBlank()) src.field1 else "Tek Kanal"
                    val url = if (src.field2.isNotBlank()) src.field2 else src.field1
                    if (url.isNotBlank()) {
                        allChannels.add(ChannelItem("single_${src.id}", name, url, "LIVE"))
                    }
                }
                "XTREAM" -> {
                    val host = src.field1.trimEnd('/')
                    val user = src.field2
                    val pass = src.field3
                    if (host.isNotBlank() && user.isNotBlank()) {
                        val liveUrl = "$host/live/$user/$pass/1.m3u8"
                        allChannels.add(ChannelItem("xt_${src.id}_1", "Xtream Canlı ($user)", liveUrl, "LIVE"))
                    }
                }
                "MAG" -> {
                    val portal = src.field1.trimEnd('/')
                    val mac = src.field2
                    if (portal.isNotBlank() && mac.isNotBlank()) {
                        allChannels.add(ChannelItem("mag_${src.id}_1", "MAG Portal ($mac)", portal, "LIVE"))
                    }
                }
                "M3U" -> {
                    val text = src.rawM3u
                    if (text.isNotBlank()) {
                        parseM3uText(text, src.id)
                    } else if (src.field1.isNotBlank()) {
                        allChannels.add(ChannelItem("m3u_${src.id}_direct", "M3U Liste", src.field1, "LIVE"))
                    }
                }
            }
        }
    }

    private fun parseM3uText(text: String, prefix: String) {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var pendingName: String? = null
        var pendingGroup = "LIVE"
        var index = 1

        for (line in lines) {
            if (line.startsWith("#EXTINF")) {
                val groupMatch = Regex("""group-title="([^"]*)"""").find(line)?.groupValues?.get(1).orEmpty().uppercase()
                pendingGroup = when {
                    groupMatch.contains("MOVIE") || groupMatch.contains("FILM") || groupMatch.contains("VOD") -> "FILM"
                    groupMatch.contains("SERIES") || groupMatch.contains("DIZI") -> "DIZI"
                    else -> "LIVE"
                }
                pendingName = line.substringAfter(",", "Kanal $index").trim()
            } else if (!line.startsWith("#")) {
                val chName = pendingName ?: "Kanal $index"
                allChannels.add(ChannelItem("${prefix}_$index", chName, line, pendingGroup))
                index++
                pendingName = null
                pendingGroup = "LIVE"
            }
        }
    }

    private fun refreshVisibleChannels() {
        visibleChannels.clear()
        when (currentTab) {
            "FAVORILER" -> {
                for (favId in favoriteIdsInOrder) {
                    allChannels.find { it.id == favId }?.let { visibleChannels.add(it) }
                }
                tvActiveSourceInfo.text = "Favoriler (Eklenme Sırasına Göre 1..N)"
            }
            "FILM" -> {
                visibleChannels.addAll(allChannels.filter { it.group == "FILM" })
                tvActiveSourceInfo.text = "Film (${visibleChannels.size})"
            }
            "DIZI" -> {
                visibleChannels.addAll(allChannels.filter { it.group == "DIZI" })
                tvActiveSourceInfo.text = "Dizi (${visibleChannels.size})"
            }
            else -> {
                visibleChannels.addAll(allChannels)
                tvActiveSourceInfo.text = "Canlı TV (${visibleChannels.size})"
            }
        }
        channelAdapter.notifyDataSetChanged()
    }

    private fun playChannel(channel: ChannelItem) {
        setFullscreen(true)
        player.setMediaItem(MediaItem.fromUri(Uri.parse(channel.url)))
        player.prepare()
        player.play()
        Toast.makeText(this, channel.name, Toast.LENGTH_SHORT).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && settingsScroll.visibility == View.VISIBLE) {
            showPlayerMode()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && isFullscreen) {
            setFullscreen(false)
            rvChannels.requestFocus()
            return true
        }

        val digit = when (keyCode) {
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> "0"
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> "1"
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> "2"
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> "3"
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> "4"
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> "5"
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> "6"
            KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> "7"
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> "8"
            KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> "9"
            else -> null
        }

        if (digit != null) {
            numberBuffer += digit
            numberHandler.removeCallbacksAndMessages(null)
            numberHandler.postDelayed({
                val targetIndex = (numberBuffer.toIntOrNull() ?: 0) - 1
                numberBuffer = ""
                if (targetIndex in visibleChannels.indices) {
                    playChannel(visibleChannels[targetIndex])
                }
            }, 450)
            return true
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun saveSources() {
        val arr = JSONArray()
        for (s in sources) {
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("type", s.type)
                put("f1", s.field1)
                put("f2", s.field2)
                put("f3", s.field3)
                put("raw", s.rawM3u)
            })
        }
        prefs.edit().putString("sources_json", arr.toString()).apply()
    }

    private fun saveFavorites() {
        prefs.edit().putString("favorites_json", JSONArray(favoriteIdsInOrder).toString()).apply()
    }

    private fun loadSavedState() {
        sources.clear()
        val srcStr = prefs.getString("sources_json", "[]") ?: "[]"
        val arr = JSONArray(srcStr)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            sources.add(
                SourceEntry(
                    id = o.optString("id"),
                    type = o.optString("type"),
                    field1 = o.optString("f1"),
                    field2 = o.optString("f2"),
                    field3 = o.optString("f3"),
                    rawM3u = o.optString("raw")
                )
            )
        }

        favoriteIdsInOrder.clear()
        val favStr = prefs.getString("favorites_json", "[]") ?: "[]"
        val favArr = JSONArray(favStr)
        for (i in 0 until favArr.length()) {
            favoriteIdsInOrder.add(favArr.getString(i))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }

    inner class ChannelAdapter(
        private val items: List<ChannelItem>,
        private val isFavorite: (ChannelItem) -> Boolean,
        private val onPlay: (ChannelItem) -> Unit,
        private val onToggleFav: (ChannelItem) -> Unit,
        private val onDelete: (ChannelItem) -> Unit
    ) : RecyclerView.Adapter<ChannelAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val row: LinearLayout = v.findViewById(R.id.channelRow)
            val tvNum: TextView = v.findViewById(R.id.tvChannelNumber)
            val tvName: TextView = v.findViewById(R.id.tvChannelName)
            val btnStar: ImageButton = v.findViewById(R.id.btnStar)
            val btnDel: ImageButton = v.findViewById(R.id.btnDeleteChannel)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvNum.text = "${position + 1}"
            holder.tvName.text = item.name
            holder.btnStar.setImageResource(
                if (isFavorite(item)) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            holder.row.setOnClickListener { onPlay(item) }
            holder.btnStar.setOnClickListener { onToggleFav(item) }
            holder.btnDel.setOnClickListener { onDelete(item) }
        }

        override fun getItemCount(): Int = items.size
    }

    inner class SourceAdapter(
        private val items: List<SourceEntry>,
        private val onEdit: (SourceEntry) -> Unit,
        private val onDelete: (SourceEntry) -> Unit
    ) : RecyclerView.Adapter<SourceAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvSummary: TextView = v.findViewById(R.id.tvSourceSummary)
            val btnEdit: Button = v.findViewById(R.id.btnEditSource)
            val btnDelete: Button = v.findViewById(R.id.btnDeleteSource)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_source, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvSummary.text = "[${item.type}] ${item.field1.ifBlank { "Yapıştırılan M3U" }}"
            holder.btnEdit.setOnClickListener { onEdit(item) }
            holder.btnDelete.setOnClickListener { onDelete(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
