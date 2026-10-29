package com.htc.traductorhibrido

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.client.generativeai.GenerativeModel
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.sin

data class IdiomaItem(val nombre: String, val codigoBcp: String, val codigoMlKit: String)
data class VozHumanaItem(val idReal: String, val etiquetaLegible: String)

class MainActivity : ComponentActivity() {
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private lateinit var audioManager: AudioManager
    private var toggleListeningCallback: (() -> Unit)? = null
    private var ttsCompletadoCallback: (() -> Unit)? = null
    private var nombreVozPreferida: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        val prefs = getSharedPreferences("config_htc", Context.MODE_PRIVATE)
        nombreVozPreferida = prefs.getString("selected_voice", "") ?: ""

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setSpeechRate(0.96f)
                tts?.setPitch(1.02f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        runOnUiThread { ttsCompletadoCallback?.invoke() }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        runOnUiThread { ttsCompletadoCallback?.invoke() }
                    }
                })
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                TraductorHibridoApp(
                    onStartListening = { langBcp, onResult -> iniciarEscucha(langBcp, onResult) },
                    onStopListening = { speechRecognizer?.stopListening() },
                    onSpeak = { texto, langBcp, modoAudio, esIdiomaA, onTerminado ->
                        ttsCompletadoCallback = onTerminado
                        hablarConEnrutamiento(texto, langBcp, modoAudio, esIdiomaA)
                    },
                    registerHeadsetToggle = { callback -> toggleListeningCallback = callback },
                    obtenerVocesFiltradas = { obtenerMejoresVoces() },
                    seleccionarVoz = { idVoz ->
                        nombreVozPreferida = idVoz
                        prefs.edit().putString("selected_voice", idVoz).apply()
                    }
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
            toggleListeningCallback?.invoke()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun obtenerMejoresVoces(): List<VozHumanaItem> {
        val todas = tts?.voices ?: return emptyList()
        val codigosInteres = listOf("es", "de", "en", "fr", "it", "pt")
        return todas
            .filter { v -> codigosInteres.any { v.locale.language.startsWith(it, ignoreCase = true) } }
            .sortedByDescending { it.quality }
            .map { v ->
                val tipo = if (v.quality >= Voice.QUALITY_HIGH || v.name.contains("network", true) || v.name.contains("wavenet", true)) {
                    "🌟 Natural HQ"
                } else {
                    "⚡ Local Rápida"
                }
                val pais = v.locale.displayLanguage.replaceFirstChar { it.uppercase() }
                VozHumanaItem(v.name, "$pais ($tipo) - ${v.name.takeLast(10)}")
            }
    }

    private fun iniciarEscucha(langBcp: String, onResult: (String) -> Unit) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langBcp)
        }
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val texto = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                onResult(texto ?: "")
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                onResult("")
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    private fun hablarConEnrutamiento(texto: String, langBcp: String, modoAudio: Int, esIdiomaA: Boolean) {
        val targetLocale = Locale.forLanguageTag(langBcp)
        tts?.language = targetLocale

        // Intentar aplicar la voz preferida del usuario si coincide con el idioma de salida, o elegir la más natural disponible
        val vocesIdioma = tts?.voices?.filter { it.locale.language == targetLocale.language } ?: emptyList()
        val vozElegida = vocesIdioma.find { it.name == nombreVozPreferida }
            ?: vocesIdioma.maxByOrNull { it.quality }
        if (vozElegida != null) {
            tts?.voice = vozElegida
        }

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "TRAD_UTT")
        }
        if (modoAudio == 1) {
            val pan = if (esIdiomaA) -1.0f else 1.0f
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, pan)
        } else {
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, 0.0f)
        }
        tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, params, "TRAD_UTT")
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        tts?.shutdown()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TraductorHibridoApp(
    onStartListening: (String, (String) -> Unit) -> Unit,
    onStopListening: () -> Unit,
    onSpeak: (String, String, Int, Boolean, () -> Unit) -> Unit,
    registerHeadsetToggle: ((() -> Unit) -> Unit),
    obtenerVocesFiltradas: () -> List<VozHumanaItem>,
    seleccionarVoz: (String) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("config_htc", Context.MODE_PRIVATE) }
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()

    var usarGemini by remember { mutableStateOf(false) }
    var modoAudio by remember { mutableStateOf(0) } // 0 = Charla, 1 = Split L/R, 2 = Reunión
    var escuchando by remember { mutableStateOf(false) }
    var escuchaIdiomaA by remember { mutableStateOf(true) }
    var modoManosLibres by remember { mutableStateOf(false) }

    var mostrarAjustesApi by remember { mutableStateOf(false) }
    var mostrarMenuMasIdiomas by remember { mutableStateOf(false) }
    var mostrarAjustesVoz by remember { mutableStateOf(false) }

    var geminiApiKey by remember { mutableStateOf(prefs.getString("gemini_key", "") ?: "") }
    var vozSeleccionada by remember { mutableStateOf(prefs.getString("selected_voice", "") ?: "") }

    val idiomasActivos = remember {
        mutableStateListOf(
            IdiomaItem("🇪🇸 Español", "es-ES", TranslateLanguage.SPANISH),
            IdiomaItem("🇩🇪 Alemán", "de-DE", TranslateLanguage.GERMAN),
            IdiomaItem("🇬🇧 Inglés", "en-US", TranslateLanguage.ENGLISH)
        )
    }
    val idiomasExtra = listOf(
        IdiomaItem("🇫🇷 Francés", "fr-FR", TranslateLanguage.FRENCH),
        IdiomaItem("🇮🇹 Italiano", "it-IT", TranslateLanguage.ITALIAN),
        IdiomaItem("🇵🇹 Portugués", "pt-PT", TranslateLanguage.PORTUGUESE),
        IdiomaItem("🇳🇱 Neerlandés", "nl-NL", TranslateLanguage.DUTCH),
        IdiomaItem("🇵🇱 Polaco", "pl-PL", TranslateLanguage.POLISH)
    )

    var idiomaMio by remember { mutableStateOf(idiomasActivos[0]) }
    var idiomaOtro by remember { mutableStateOf(idiomasActivos[1]) }

    var textoOriginal by remember { mutableStateOf("Toca el micrófono o activa Manos Libres...") }
    var textoTraducido by remember { mutableStateOf("Die Übersetzung erscheint hier...") }
    val historialReunion = remember { mutableStateListOf<String>() }

    // Animación de la doble onda neón (Estilo 5)
    val infiniteTransition = rememberInfiniteTransition(label = "Onda")
    val waveOffset1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(3000, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "Onda1"
    )
    val waveOffset2 by infiniteTransition.animateFloat(
        initialValue = 180f,
        targetValue = 540f,
        animationSpec = infiniteRepeatable(animation = tween(4200, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "Onda2"
    )

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Declaración adelantada para recursión limpia en Manos Libres
    var reanudarSiManosLibres: () -> Unit = {}

    // Función central con Auto-Detección de Idioma (Opción A) + Modo Espejo Silencioso
    fun traducirYEnrutar(textoCapturado: String, habloYoSugerido: Boolean) {
        val languageIdentifier = LanguageIdentification.getClient()
        languageIdentifier.identifyLanguage(textoCapturado)
            .addOnSuccessListener { langCodeDetectado ->
                // Detecta automáticamente quién habló (Opción A) o usa el turno sugerido si la frase es muy corta
                val habloYoReal = when {
                    langCodeDetectado.startsWith(idiomaMio.codigoMlKit, ignoreCase = true) -> true
                    langCodeDetectado.startsWith(idiomaOtro.codigoMlKit, ignoreCase = true) -> false
                    else -> habloYoSugerido
                }
                val origen = if (habloYoReal) idiomaMio else idiomaOtro
                val destino = if (habloYoReal) idiomaOtro else idiomaMio

                // REGLA MODO ESPEJO (Página 0): Solo texto, cero voz para velocidad máxima
                val reproducirVoz = (pagerState.currentPage != 0)
                textoOriginal = "${origen.nombre}: $textoCapturado"

                if (!usarGemini) {
                    val options = TranslatorOptions.Builder()
                        .setSourceLanguage(origen.codigoMlKit)
                        .setTargetLanguage(destino.codigoMlKit)
                        .build()
                    val translator = Translation.getClient(options)
                    textoTraducido = "⏳ Traduciendo..."
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                        .addOnSuccessListener {
                            translator.translate(textoCapturado)
                                .addOnSuccessListener { resultado ->
                                    textoTraducido = "${destino.nombre}: $resultado"
                                    if (modoAudio == 2) {
                                        historialReunion.add("[${origen.nombre} -> ${destino.nombre}] $textoCapturado => $resultado")
                                    }
                                    if (reproducirVoz) {
                                        onSpeak(resultado, destino.codigoBcp, modoAudio, !habloYoReal) {
                                            reanudarSiManosLibres()
                                        }
                                    } else {
                                        reanudarSiManosLibres()
                                    }
                                }
                                .addOnFailureListener { reanudarSiManosLibres() }
                        }
                        .addOnFailureListener { reanudarSiManosLibres() }
                } else {
                    if (geminiApiKey.isBlank()) {
                        textoTraducido = "⚠️ Configura tu API Key en el icono ⚙️"
                        modoManosLibres = false
                        return@addOnSuccessListener
                    }
                    textoTraducido = "🧠 Gemini IA traduciendo..."
                    coroutineScope.launch {
                        try {
                            val model = GenerativeModel(modelName = "gemini-2.5-flash", apiKey = geminiApiKey)
                            val prompt = "Actúa como intérprete simultáneo. Si el texto está en ${idiomaMio.nombre} tradúcelo a ${idiomaOtro.nombre}, y si está en ${idiomaOtro.nombre} tradúcelo a ${idiomaMio.nombre}. Devuelve SOLO la traducción natural sin explicaciones: \"$textoCapturado\""
                            val response = model.generateContent(prompt)
                            val resultado = response.text?.trim() ?: ""
                            textoTraducido = "${destino.nombre}: $resultado"
                            if (modoAudio == 2) {
                                historialReunion.add("[${origen.nombre} -> ${destino.nombre}] $textoCapturado => $resultado")
                            }
                            if (reproducirVoz && resultado.isNotBlank()) {
                                onSpeak(resultado, destino.codigoBcp, modoAudio, !habloYoReal) {
                                    reanudarSiManosLibres()
                                }
                            } else {
                                reanudarSiManosLibres()
                            }
                        } catch (e: Exception) {
                            textoTraducido = "Error Gemini: ${e.localizedMessage}"
                            reanudarSiManosLibres()
                        }
                    }
                }
            }
            .addOnFailureListener {
                reanudarSiManosLibres()
            }
    }

    reanudarSiManosLibres = {
        if (modoManosLibres || modoAudio == 2) {
            coroutineScope.launch {
                delay(650)
                if (modoManosLibres || modoAudio == 2) {
                    escuchaIdiomaA = !escuchaIdiomaA
                    val proximoIdioma = if (escuchaIdiomaA) idiomaMio else idiomaOtro
                    escuchando = true
                    onStartListening(proximoIdioma.codigoBcp) { capturado ->
                        escuchando = false
                        if (capturado.isNotBlank()) {
                            traducirYEnrutar(capturado, escuchaIdiomaA)
                        } else {
                            reanudarSiManosLibres()
                        }
                    }
                }
            }
        }
    }

    fun accionarMicrofonoManual(habloYo: Boolean) {
        if (escuchando) {
            modoManosLibres = false
            onStopListening()
            escuchando = false
        } else {
            escuchando = true
            escuchaIdiomaA = habloYo
            val idiomaEscucha = if (habloYo) idiomaMio else idiomaOtro
            onStartListening(idiomaEscucha.codigoBcp) { textoCapturado ->
                escuchando = false
                if (textoCapturado.isNotBlank()) {
                    traducirYEnrutar(textoCapturado, habloYo)
                } else if (modoManosLibres) {
                    reanudarSiManosLibres()
                }
            }
        }
    }

    LaunchedEffect(idiomaMio, idiomaOtro, modoAudio, usarGemini) {
        registerHeadsetToggle {
            modoManosLibres = !modoManosLibres
            if (modoManosLibres) {
                accionarMicrofonoManual(habloYo = false)
            } else {
                onStopListening()
                escuchando = false
            }
        }
    }

    // =========================================================================
    // INTERFAZ VISUAL ESTILO 5 (Cristal Cian Translúcido + Micropuntos + Onda)
    // =========================================================================
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050811))
    ) {
        // Textura de fondo de micropuntos (Dot-Matrix sutil)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stepPx = 16.dp.toPx().toInt().coerceAtLeast(10)
            for (x in 0..size.width.toInt() step stepPx) {
                for (y in 0..size.height.toInt() step stepPx) {
                    drawCircle(
                        color = Color(0x0A00F0FF),
                        radius = 1.dp.toPx(),
                        center = Offset(x.toFloat(), y.toFloat())
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // Cabecera Superior con Indicadores L / R de Audífonos HTC + Botón Voces 🗣️ y API ⚙️
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x1A00F0FF))
                            .border(1.dp, Color(0x5500F0FF), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text("L ▂▄▆█", color = Color(0xFF00F0FF), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                    Text(
                        text = when (pagerState.currentPage) {
                            0 -> "ESPEJO 180° (SOLO TEXTO)"
                            1 -> "HTC AI TRANSLATOR"
                            else -> "MODO BOLSILLO"
                        },
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x1A00FFAA))
                            .border(1.dp, Color(0x5500FFAA), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text("█▆▄▂ R", color = Color(0xFF00FFAA), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }

                Row {
                    IconButton(onClick = { mostrarAjustesVoz = true }) {
                        Text("🗣️", fontSize = 18.sp)
                    }
                    IconButton(onClick = { mostrarAjustesApi = true }) {
                        Text("⚙️", fontSize = 18.sp)
                    }
                }
            }

            // Carrusel de 3 pantallas
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                when (page) {
                    // PÁGINA 0: MODO ESPEJO 180° (SILENCIOSO - SOLO TEXTO ULTRARRÁPIDO)
                    0 -> {
                        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .rotate(180f)
                                    .border(1.5.dp, Brush.linearGradient(listOf(Color(0xFF00F0FF), Color(0x2200F0FF))), RoundedCornerShape(22.dp))
                                    .clickable { accionarMicrofonoManual(habloYo = false) },
                                shape = RoundedCornerShape(22.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0x12162238))
                            ) {
                                Box(modifier = Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("🔇 MODO LECTURA RÁPIDA (${idiomaOtro.nombre})", color = Color(0xFF00F0FF), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(textoTraducido, color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            // Píldora central de Manos Libres en Modo Espejo
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .clip(RoundedCornerShape(50))
                                    .background(if (modoManosLibres) Color(0x33FF007A) else Color(0x1A00F0FF))
                                    .border(1.dp, if (modoManosLibres) Color(0xFFFF007A) else Color(0xFF00F0FF), RoundedCornerShape(50))
                                    .clickable {
                                        modoManosLibres = !modoManosLibres
                                        if (modoManosLibres) accionarMicrofonoManual(habloYo = true) else onStopListening()
                                    }
                                    .padding(horizontal = 20.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = if (modoManosLibres) "🔄 CONVERSACIÓN FLUIDA ACTIVA (SIN PULSAR)" else "▶️ ACTIVAR CHARLA AUTOMÁTICA SIN PULSAR",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .border(1.5.dp, Brush.linearGradient(listOf(Color(0xFF00FFAA), Color(0x2200FFAA))), RoundedCornerShape(22.dp))
                                    .clickable { accionarMicrofonoManual(habloYo = true) },
                                shape = RoundedCornerShape(22.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0x12162238))
                            ) {
                                Box(modifier = Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("🔇 MODO LECTURA RÁPIDA (${idiomaMio.nombre})", color = Color(0xFF00FFAA), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(textoOriginal, color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    // PÁGINA 1: CENTRO DE MANDO (DISEÑO 5)
                    1 -> {
                        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Selector de Motor Híbrido
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { usarGemini = false },
                                    modifier = Modifier
                                        .weight(1f)
                                        .border(1.dp, if (!usarGemini) Color(0xFF00F0FF) else Color(0x22FFFFFF), RoundedCornerShape(14.dp)),
                                    colors = ButtonDefaults.buttonColors(containerColor = if (!usarGemini) Color(0x2200F0FF) else Color(0x08FFFFFF)),
                                    shape = RoundedCornerShape(14.dp)
                                ) { Text("⚡ Google Offline", color = Color.White, fontSize = 12.sp) }

                                Button(
                                    onClick = { usarGemini = true },
                                    modifier = Modifier
                                        .weight(1f)
                                        .border(1.dp, if (usarGemini) Color(0xFF00FFAA) else Color(0x22FFFFFF), RoundedCornerShape(14.dp)),
                                    colors = ButtonDefaults.buttonColors(containerColor = if (usarGemini) Color(0x2200FFAA) else Color(0x08FFFFFF)),
                                    shape = RoundedCornerShape(14.dp)
                                ) { Text("🧠 Gemini IA Live", color = Color.White, fontSize = 12.sp) }
                            }

                            // Selector de Modo de Audio
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val modos = listOf("🗣️ Charla", "🎧 Split L/R", "📻 Reunión")
                                modos.forEachIndexed { index, titulo ->
                                    OutlinedButton(
                                        onClick = { modoAudio = index },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = if (modoAudio == index) Color(0x1F00F0FF) else Color.Transparent
                                        ),
                                        border = BorderStroke(1.dp, if (modoAudio == index) Color(0xFF00F0FF) else Color(0x22FFFFFF))
                                    ) {
                                        Text(titulo, color = if (modoAudio == index) Color(0xFF00F0FF) else Color.LightGray, fontSize = 11.sp)
                                    }
                                }
                            }

                            // Tarjeta Superior: Cristal Ahumado Cian (Idioma 1)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(Brush.verticalGradient(listOf(Color(0x1F0C243C), Color(0x0D07111E))))
                                    .border(
                                        width = 1.2.dp,
                                        brush = Brush.linearGradient(listOf(Color(0xFF00F0FF), Color(0x1100F0FF), Color(0x5500F0FF))),
                                        shape = RoundedCornerShape(22.dp)
                                    )
                                    .padding(16.dp)
                            ) {
                                Column {
                                    Text(idiomaMio.nombre, color = Color(0xFF00F0FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(textoOriginal, color = Color.White, fontSize = 17.sp, lineHeight = 23.sp)
                                }
                            }

                            // Píldora luminosa central estilo Diseño 5 ("AI translation active / Manos Libres")
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .clip(RoundedCornerShape(50))
                                    .background(if (modoManosLibres) Color(0x33FF007A) else Color(0x1800F0FF))
                                    .border(
                                        1.dp,
                                        if (modoManosLibres) Color(0xFFFF007A) else Color(0x8800F0FF),
                                        RoundedCornerShape(50)
                                    )
                                    .clickable {
                                        modoManosLibres = !modoManosLibres
                                        if (modoManosLibres) {
                                            accionarMicrofonoManual(habloYo = true)
                                        } else {
                                            onStopListening()
                                            escuchando = false
                                        }
                                    }
                                    .padding(horizontal = 22.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = if (modoManosLibres) "🔊)) Manos Libres Activo (Toca para pausar)" else "🎙️)) Toca aquí para Manos Libres Automático",
                                    color = if (modoManosLibres) Color(0xFFFF66B2) else Color(0xFF7CEEFF),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Tarjeta Inferior: Cristal Ahumado Cian/Esmeralda (Idioma 2)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(Brush.verticalGradient(listOf(Color(0x1F0C243C), Color(0x0D07111E))))
                                    .border(
                                        width = 1.2.dp,
                                        brush = Brush.linearGradient(listOf(Color(0xFF00FFAA), Color(0x1100FFAA), Color(0x5500F0FF))),
                                        shape = RoundedCornerShape(22.dp)
                                    )
                                    .padding(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(idiomaOtro.nombre, color = Color(0xFF00FFAA), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(textoTraducido, color = Color.White, fontSize = 17.sp, lineHeight = 23.sp)
                                    }

                                    if (modoAudio == 2) {
                                        Button(
                                            onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                clipboard.setPrimaryClip(ClipData.newPlainText("Acta", historialReunion.joinToString("\n")))
                                                Toast.makeText(context, "✅ Acta copiada (${historialReunion.size} frases)", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.fillMaxWidth().height(34.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0x2800F0FF))
                                        ) {
                                            Text("💾 Guardar Historial Reunión (${historialReunion.size})", color = Color(0xFF00F0FF), fontSize = 11.sp)
                                        }
                                    }
                                }
                            }

                            // Barra de Idiomas Rápidos + Botón [+]
                            LazyRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(idiomasActivos) { item ->
                                    val activo = (item == idiomaMio || item == idiomaOtro)
                                    OutlinedButton(
                                        onClick = { if (item != idiomaMio) idiomaOtro = item },
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = if (activo) Color(0x2200F0FF) else Color.Transparent
                                        ),
                                        border = BorderStroke(1.dp, if (activo) Color(0xFF00F0FF) else Color(0x22FFFFFF))
                                    ) {
                                        Text(item.nombre, color = Color.White, fontSize = 11.sp)
                                    }
                                }
                                item {
                                    OutlinedButton(
                                        onClick = { mostrarMenuMasIdiomas = true },
                                        border = BorderStroke(1.dp, Color(0xFF00FFAA))
                                    ) {
                                        Text("➕ Idioma", color = Color(0xFF00FFAA), fontSize = 11.sp)
                                    }
                                }
                            }

                            // Botones circulares/inferiores de control rápido
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = { accionarMicrofonoManual(habloYo = true) },
                                    border = BorderStroke(1.dp, Color(0xFF00F0FF))
                                ) {
                                    Text("🎙️ ${idiomaMio.nombre}", color = Color.White, fontSize = 12.sp)
                                }

                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(if (escuchando) Color(0xFFFF0055) else Color(0x2200F0FF))
                                        .border(2.dp, Color(0xFF00F0FF), CircleShape)
                                        .clickable {
                                            modoManosLibres = !modoManosLibres
                                            if (modoManosLibres) accionarMicrofonoManual(habloYo = true) else onStopListening()
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(if (escuchando) "⏹️" else "🎙️", fontSize = 22.sp)
                                }

                                OutlinedButton(
                                    onClick = { accionarMicrofonoManual(habloYo = false) },
                                    border = BorderStroke(1.dp, Color(0xFF00FFAA))
                                ) {
                                    Text("👂 ${idiomaOtro.nombre}", color = Color.White, fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    // PÁGINA 2: MODO BOLSILLO (OLED)
                    2 -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable {
                                    modoManosLibres = !modoManosLibres
                                    if (modoManosLibres) accionarMicrofonoManual(habloYo = false) else onStopListening()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(220.dp)
                                        .background(
                                            if (modoManosLibres || escuchando) Color(0x22FF007A) else Color(0x1100F0FF),
                                            shape = CircleShape
                                        )
                                        .border(
                                            2.dp,
                                            if (modoManosLibres || escuchando) Color(0xFFFF007A) else Color(0xFF00F0FF),
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (modoManosLibres || escuchando) "🔊\nMANOS LIBRES\nACTIVO" else "🎧\nTOCAR PARA\nMANOS LIBRES",
                                        color = Color.White,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Onda Luminosa Neón en la base (Cian + Violeta/Magenta - Diseño 5)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(top = 6.dp)
            ) {
                val path1 = Path()
                val path2 = Path()
                val midY = size.height / 2f
                val amp1 = if (escuchando || modoManosLibres) 14.dp.toPx() else 5.dp.toPx()
                val amp2 = if (escuchando || modoManosLibres) 11.dp.toPx() else 4.dp.toPx()

                path1.moveTo(0f, midY)
                path2.moveTo(0f, midY)

                for (x in 0..size.width.toInt() step 6) {
                    val rad1 = Math.toRadians((x * 0.7) + waveOffset1)
                    val rad2 = Math.toRadians((x * 0.5) + waveOffset2)
                    val y1 = midY + (sin(rad1).toFloat() * amp1)
                    val y2 = midY + (sin(rad2).toFloat() * amp2)
                    path1.lineTo(x.toFloat(), y1)
                    path2.lineTo(x.toFloat(), y2)
                }

                drawPath(
                    path = path1,
                    brush = Brush.horizontalGradient(listOf(Color(0xFF00F0FF), Color(0xFF7B2CBF), Color(0xFF00F0FF))),
                    style = Stroke(width = 2.5.dp.toPx())
                )
                drawPath(
                    path = path2,
                    brush = Brush.horizontalGradient(listOf(Color(0xFFFF007A), Color(0xFF00F0FF), Color(0xFFFF007A))),
                    style = Stroke(width = 1.8.dp.toPx())
                )
            }
        }
    }

    // Ventana Selector de Voces Naturales (Opción 1)
    if (mostrarAjustesVoz) {
        val listaVoces = remember { obtenerVocesFiltradas() }
        AlertDialog(
            onDismissRequest = { mostrarAjustesVoz = false },
            title = { Text("🗣️ Elegir Voz Humana de Salida") },
            text = {
                Column {
                    Text(
                        "Las voces marcadas con 🌟 Natural HQ son las voces neuronales de Google menos robóticas:",
                        fontSize = 12.sp,
                        color = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyColumn(modifier = Modifier.height(260.dp)) {
                        items(listaVoces) { itemVoz ->
                            val esElegida = (itemVoz.idReal == vozSeleccionada)
                            TextButton(
                                onClick = {
                                    vozSeleccionada = itemVoz.idReal
                                    seleccionarVoz(itemVoz.idReal)
                                    mostrarAjustesVoz = false
                                    Toast.makeText(context, "Voz actualizada: ${itemVoz.etiquetaLegible}", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (esElegida) "✅ ${itemVoz.etiquetaLegible}" else itemVoz.etiquetaLegible,
                                    color = if (esElegida) Color(0xFF00F0FF) else Color.White,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { mostrarAjustesVoz = false }) { Text("Cerrar") }
            }
        )
    }

    // Ventana para Agregar Idiomas o Cambiar Idioma Propio
    if (mostrarMenuMasIdiomas) {
        AlertDialog(
            onDismissRequest = { mostrarMenuMasIdiomas = false },
            title = { Text("➕ Configurar Idiomas") },
            text = {
                LazyColumn(modifier = Modifier.height(280.dp)) {
                    item { Text("Agregar nuevo idioma a la barra:", fontSize = 12.sp, color = Color.Gray) }
                    items(idiomasExtra) { extra ->
                        TextButton(
                            onClick = {
                                if (!idiomasActivos.contains(extra)) idiomasActivos.add(extra)
                                idiomaOtro = extra
                                mostrarMenuMasIdiomas = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(extra.nombre, fontSize = 15.sp) }
                    }
                    item {
                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                        Text("Cambiar MI idioma principal (Oído Izquierdo):", fontSize = 12.sp, color = Color.Gray)
                    }
                    items(idiomasActivos) { activo ->
                        TextButton(
                            onClick = {
                                idiomaMio = activo
                                mostrarMenuMasIdiomas = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Usar ${activo.nombre} como mi idioma", fontSize = 14.sp) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { mostrarMenuMasIdiomas = false }) { Text("Cerrar") }
            }
        )
    }

    // Ventana API Key de Gemini
    if (mostrarAjustesApi) {
        AlertDialog(
            onDismissRequest = { mostrarAjustesApi = false },
            title = { Text("🔑 Configurar Llave Gemini IA") },
            text = {
                Column {
                    Text("Pega tu API Key de Google AI Studio (opcional, el modo Google Offline funciona sin llave):", fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = geminiApiKey,
                        onValueChange = { geminiApiKey = it },
                        placeholder = { Text("AIzaSy...") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    prefs.edit().putString("gemini_key", geminiApiKey.trim()).apply()
                    mostrarAjustesApi = false
                    Toast.makeText(context, "API Key guardada", Toast.LENGTH_SHORT).show()
                }) { Text("Guardar") }
            }
        )
    }
}
