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
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.client.generativeai.GenerativeModel
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

data class IdiomaItem(val nombre: String, val codigoBcp: String, val codigoMlKit: String)

class MainActivity : ComponentActivity() {
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private lateinit var audioManager: AudioManager
    private var toggleListeningCallback: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        tts = TextToSpeech(this) { }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                TraductorHibridoApp(
                    onStartListening = { langBcp, onResult -> iniciarEscucha(langBcp, onResult) },
                    onStopListening = { speechRecognizer?.stopListening() },
                    onSpeak = { texto, langBcp, modoAudio, esIdiomaA -> hablarConEnrutamiento(texto, langBcp, modoAudio, esIdiomaA) },
                    registerHeadsetToggle = { callback -> toggleListeningCallback = callback }
                )
            }
        }
    }

    // Captura el toque táctil de los audífonos HTC para activar/pausar micrófono
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
            toggleListeningCallback?.invoke()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun iniciarEscucha(langBcp: String, onResult: (String) -> Unit) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langBcp)
        }
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val texto = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!texto.isNullOrBlank()) onResult(texto)
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    private fun hablarConEnrutamiento(texto: String, langBcp: String, modoAudio: Int, esIdiomaA: Boolean) {
        tts?.language = Locale.forLanguageTag(langBcp)
        val params = Bundle()

        // Enrutamiento de canales L/R según el modo elegido
        when (modoAudio) {
            1 -> { // Modo Split L/R: Idioma A al oído Izquierdo (-1.0), Idioma B al Derecho (+1.0)
                val pan = if (esIdiomaA) -1.0f else 1.0f
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, pan)
            }
            else -> {
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, 0.0f)
            }
        }
        tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, params, "TRAD_ID")
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
    onSpeak: (String, String, Int, Boolean) -> Unit,
    registerHeadsetToggle: ((() -> Unit) -> Unit)
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("config_htc", Context.MODE_PRIVATE) }

    // Pager: Página 0 = Espejo 180°, Página 1 = Centro de Mando (Inicio), Página 2 = Modo Bolsillo
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })

    // Estados de configuración
    var usarGemini by remember { mutableStateOf(false) } // false = Google Offline, true = Gemini IA
    var modoAudio by remember { mutableIntStateOf(0) } // 0 = Charla, 1 = Split L/R, 2 = Reunión
    var escuchando by remember { mutableStateOf(false) }
    var escuchaIdiomaA by remember { mutableStateOf(true) } // Alterna quién habla
    var mostrarAjustesApi by remember { mutableStateOf(false) }
    var mostrarMenuMasIdiomas by remember { mutableStateOf(false) }
    var geminiApiKey by remember { mutableStateOf(prefs.getString("gemini_key", "") ?: "") }

    // Lista de idiomas predeterminados + expansibles
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

    var idiomaMio by remember { mutableStateOf(idiomasActivos[0]) } // Español
    var idiomaOtro by remember { mutableStateOf(idiomasActivos[1]) } // Alemán

    var textoOriginal by remember { mutableStateOf("Toca el micrófono o tu audífono HTC para hablar...") }
    var textoTraducido by remember { mutableStateOf("Die Übersetzung erscheint hier...") }
    val historialReunion = remember { mutableStateListOf<String>() }

    // Solicitud automática de permiso de micrófono
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Función central que procesa y traduce (Google Offline o Gemini IA)
    fun procesarTraduccion(textoEntrada: String, origen: IdiomaItem, destino: IdiomaItem, habloYo: Boolean) {
        textoOriginal = "${origen.nombre}: $textoEntrada"
        if (!usarGemini) {
            // MOTOR 1: GOOGLE TRANSLATE OFFLINE (ML KIT)
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(origen.codigoMlKit)
                .setTargetLanguage(destino.codigoMlKit)
                .build()
            val translator = Translation.getClient(options)
            val conditions = DownloadConditions.Builder().build()

            textoTraducido = "⏳ Verificando diccionario offline..."
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener {
                    translator.translate(textoEntrada)
                        .addOnSuccessListener { resultado ->
                            textoTraducido = "${destino.nombre}: $resultado"
                            if (modoAudio == 2) {
                                historialReunion.add("[${origen.nombre} -> ${destino.nombre}] $textoEntrada => $resultado")
                            }
                            onSpeak(resultado, destino.codigoBcp, modoAudio, !habloYo)
                        }
                }
                .addOnFailureListener {
                    textoTraducido = "Error al descargar diccionario: ${it.localizedMessage}"
                }
        } else {
            // MOTOR 2: GEMINI IA CONTEXTUAL
            if (geminiApiKey.isBlank()) {
                textoTraducido = "⚠️ Toca el engranaje ⚙️ arriba y pega tu API Key de Google AI Studio."
                return
            }
            textoTraducido = "🧠 Gemini traduciendo contexto..."
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val model = GenerativeModel(modelName = "gemini-2.5-flash", apiKey = geminiApiKey)
                    val prompt = "Actúa como intérprete simultáneo profesional. Traduce del ${origen.nombre} al ${destino.nombre} manteniendo el tono natural y contexto exacto. Devuelve SOLO la traducción sin explicaciones: \"$textoEntrada\""
                    val response = model.generateContent(prompt)
                    val resultado = response.text?.trim() ?: ""
                    textoTraducido = "${destino.nombre}: $resultado"
                    if (modoAudio == 2) {
                        historialReunion.add("[${origen.nombre} -> ${destino.nombre}] $textoEntrada => $resultado")
                    }
                    onSpeak(resultado, destino.codigoBcp, modoAudio, !habloYo)
                } catch (e: Exception) {
                    textoTraducido = "Error Gemini IA: ${e.localizedMessage}"
                }
            }
        }
    }

    fun accionarMicrofono(habloYo: Boolean) {
        if (escuchando) {
            onStopListening()
            escuchando = false
        } else {
            escuchando = true
            escuchaIdiomaA = habloYo
            val origen = if (habloYo) idiomaMio else idiomaOtro
            val destino = if (habloYo) idiomaOtro else idiomaMio
            onStartListening(origen.codigoBcp) { textoCapturado ->
                escuchando = false
                procesarTraduccion(textoCapturado, origen, destino, habloYo)
                // Si está en Modo Reunión, vuelve a escuchar continuamente
                if (modoAudio == 2) {
                    accionarMicrofono(false)
                }
            }
        }
    }

    // Conectar el botón físico del audífono HTC
    LaunchedEffect(idiomaMio, idiomaOtro, modoAudio, usarGemini) {
        registerHeadsetToggle { accionarMicrofono(habloYo = false) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1117))
            .padding(12.dp)
    ) {
        // Indicador superior de las 3 vistas deslizables
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when (pagerState.currentPage) {
                    0 -> "⬅️ Modo Espejo 180° (Mesa)"
                    1 -> "🎧 Centro de Mando HTC"
                    else -> "Modo Bolsillo (OLED) ➡️"
                },
                color = Color(0xFF58A6FF),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            IconButton(onClick = { mostrarAjustesApi = true }) {
                Text("⚙️", fontSize = 20.sp)
            }
        }

        // CARRUSEL DE 3 PANTALLAS (Deslizar Izquierda / Centro / Derecha)
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { page ->
            when (page) {
                // =========================================================
                // PÁGINA 0 (IZQUIERDA): MODO PANTALLA DIVIDIDA 180° (ESPEJO)
                // =========================================================
                0 -> {
                    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Mitad Superior GIRADA 180 GRADOS para la persona frente a ti
                        Card(
                            modifier = Modifier.fillMaxWidth().weight(1f).rotate(180f)
                                .clickable { accionarMicrofono(habloYo = false) },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2937))
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(16.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("TOCA PARA HABLAR (${idiomaOtro.nombre})", color = Color(0xFF9CA3AF), fontSize = 13.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(textoTraducido, color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Mitad Inferior NORMAL para ti
                        Card(
                            modifier = Modifier.fillMaxWidth().weight(1f)
                                .clickable { accionarMicrofono(habloYo = true) },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(16.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("TOCA PARA HABLAR (${idiomaMio.nombre})", color = Color(0xFF58A6FF), fontSize = 13.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(textoOriginal, color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // =========================================================
                // PÁGINA 1 (CENTRO): CENTRO DE MANDO PRINCIPAL (OPCIÓN 1)
                // =========================================================
                1 -> {
                    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // 1. Selector de Motor: Google Offline vs Gemini IA
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { usarGemini = false },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (!usarGemini) Color(0xFF2563EB) else Color(0xFF1F2937)
                                )
                            ) { Text("⚡ Google Offline", color = Color.White, fontSize = 13.sp) }

                            Button(
                                onClick = { usarGemini = true },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (usarGemini) Color(0xFF059669) else Color(0xFF1F2937)
                                )
                            ) { Text("🧠 Gemini IA Live", color = Color.White, fontSize = 13.sp) }
                        }

                        // 2. Selector de Modo de Audio para los Audífonos HTC
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val modos = listOf("🗣️ Charla", "🎧 Split L/R", "📻 Reunión")
                            modos.forEachIndexed { index, titulo ->
                                OutlinedButton(
                                    onClick = { modoAudio = index },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (modoAudio == index) Color(0xFF374151) else Color.Transparent
                                    )
                                ) {
                                    Text(titulo, color = Color.White, fontSize = 11.sp)
                                }
                            }
                        }

                        // 3. Pizarra de Conversación en Tiempo Real
                        Card(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22))
                        ) {
                            Column(modifier = Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text("ENTRADA CAPTURADA:", color = Color.Gray, fontSize = 11.sp)
                                    Text(textoOriginal, color = Color.White, fontSize = 17.sp, modifier = Modifier.padding(top = 4.dp))
                                    Divider(modifier = Modifier.padding(vertical = 12.dp), color = Color.DarkGray)
                                    Text("TRADUCCIÓN:", color = Color(0xFF58A6FF), fontSize = 11.sp)
                                    Text(textoTraducido, color = Color(0xFF3FB950), fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                                }

                                // Botón de Guardar Historial (EXCLUSIVO PARA MODO REUNIÓN)
                                if (modoAudio == 2) {
                                    Button(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val textoCompleto = historialReunion.joinToString("\n")
                                            clipboard.setPrimaryClip(ClipData.newPlainText("Acta Reunion", textoCompleto))
                                            Toast.makeText(context, "✅ Historial copiado (${historialReunion.size} líneas)", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6E40C9))
                                    ) {
                                        Text("💾 Copiar / Guardar Historial de Reunión (${historialReunion.size})", color = Color.White, fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                        // 4. Selector de Idiomas Activos + Botón [+] para agregar más
                        Text("TU IDIOMA (Oído Izq)  ⇄  IDIOMA INTERLOCUTOR (Oído Der):", color = Color.Gray, fontSize = 11.sp)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            idiomasActivos.forEach { item ->
                                val esSeleccionado = (item == idiomaMio || item == idiomaOtro)
                                FilterChip(
                                    selected = esSeleccionado,
                                    onClick = {
                                        if (item != idiomaMio) idiomaOtro = item
                                    },
                                    label = { Text(item.nombre, fontSize = 11.sp) }
                                )
                            }
                            AssistChip(
                                onClick = { mostrarMenuMasIdiomas = true },
                                label = { Text("➕", fontSize = 12.sp) }
                            )
                        }

                        // 5. Botones Grandes de Acción (Hablar Yo / Escuchar al Otro)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { accionarMicrofono(habloYo = true) },
                                modifier = Modifier.weight(1f).height(64.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (escuchando && escuchaIdiomaA) Color(0xFFDC2626) else Color(0xFF1D4ED8)
                                )
                            ) {
                                Text(if (escuchando && escuchaIdiomaA) "⏹️ Escuchando..." else "🎙️ Hablar (${idiomaMio.nombre})", color = Color.White)
                            }

                            Button(
                                onClick = { accionarMicrofono(habloYo = false) },
                                modifier = Modifier.weight(1f).height(64.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (escuchando && !escuchaIdiomaA) Color(0xFFDC2626) else Color(0xFF047857)
                                )
                            ) {
                                Text(if (escuchando && !escuchaIdiomaA) "⏹️ Escuchando..." else "👂 Escuchar (${idiomaOtro.nombre})", color = Color.White)
                            }
                        }
                    }
                }

                // =========================================================
                // PÁGINA 2 (DERECHA): MODO BOLSILLO MINIMALISTA (PURO AUDIO)
                // =========================================================
                2 -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .clickable { accionarMicrofono(habloYo = false) },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(220.dp)
                                    .background(
                                        if (escuchando) Color(0xFFDC2626) else Color(0xFF1F2937),
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (escuchando) "🎙️\nESCUCHANDO" else "🎧\nTOCAR O USAR\nAUDÍFONO HTC",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "${idiomaOtro.nombre} ➔ ${idiomaMio.nombre}",
                                color = Color.Gray,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "Pantalla en bajo consumo para llevar en el bolsillo",
                                color = Color.DarkGray,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Ventana Emergente para Agregar Idiomas Adicionales
    if (mostrarMenuMasIdiomas) {
        AlertDialog(
            onDismissRequest = { mostrarMenuMasIdiomas = false },
            title = { Text("➕ Agregar Idioma o Cambiar Tu Idioma Base") },
            text = {
                LazyColumn {
                    item { Text("Toca un idioma extra para añadirlo a tu barra:", fontSize = 12.sp, color = Color.Gray) }
                    items(idiomasExtra) { extra ->
                        TextButton(
                            onClick = {
                                if (!idiomasActivos.contains(extra)) idiomasActivos.add(extra)
                                idiomaOtro = extra
                                mostrarMenuMasIdiomas = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(extra.nombre, fontSize = 16.sp) }
                    }
                    item {
                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                        Text("Cambiar TU idioma principal (Oído Izquierdo):", fontSize = 12.sp, color = Color.Gray)
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

    // Ventana Emergente para pegar tu API Key de Google AI Studio
    if (mostrarAjustesApi) {
        AlertDialog(
            onDismissRequest = { mostrarAjustesApi = false },
            title = { Text("🔑 Configurar Llave Gemini IA") },
            text = {
                Column {
                    Text("Pega aquí tu API Key de Google AI Studio (el modo Google Offline no requiere llave):", fontSize = 13.sp)
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
                    Toast.makeText(context, "API Key guardada en tu teléfono", Toast.LENGTH_SHORT).show()
                }) { Text("Guardar") }
            }
        )
    }
}
