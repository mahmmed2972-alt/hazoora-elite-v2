package com.hazoora.quizgame

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

data class QuizQuestion(
    val question: String,
    val correct: String,
    val wrong1: String,
    val wrong2: String,
    val hint: String,
    val visual: String? = null,
    val category: String = "general"
) {
    // مفتاح فريد حتى أسئلة الأعلام التي تحمل نفس نص السؤال لا تُحسب كسؤال واحد.
    val uniqueKey: String
        get() = "$question|$correct|${visual.orEmpty()}"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MobileAds.initialize(this) {}

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PlayGame(this)
                }
            }
        }
    }
}

@Composable
fun PlayGame(context: Context) {
    val preferences = remember {
        context.getSharedPreferences("PLAY_GAME", Context.MODE_PRIVATE)
    }

    // موسيقى هادئة تعمل بالخلفية طوال اللعبة.
    val backgroundPlayer = remember {
        MediaPlayer.create(context, R.raw.background_music).apply {
            isLooping = true
            setVolume(0.28f, 0.28f)
        }
    }

    DisposableEffect(Unit) {
        if (!backgroundPlayer.isPlaying) {
            backgroundPlayer.start()
        }

        onDispose {
            backgroundPlayer.stop()
            backgroundPlayer.release()
        }
    }

    var screen by rememberSaveable { mutableStateOf("home") }
    var stage by rememberSaveable { mutableIntStateOf(1) }
    var unlockedStage by rememberSaveable {
        mutableIntStateOf(preferences.getInt("unlocked_stage", 1).coerceIn(1, 20))
    }
    var questionIndex by rememberSaveable { mutableIntStateOf(0) }
    var score by rememberSaveable { mutableIntStateOf(0) }
    var correctCount by rememberSaveable { mutableIntStateOf(0) }
    var wrongCount by rememberSaveable { mutableIntStateOf(0) }
    var hearts by rememberSaveable { mutableIntStateOf(3) }
    var muted by rememberSaveable { mutableStateOf(false) }
    var restartKey by rememberSaveable { mutableIntStateOf(0) }
    var stageCompleted by rememberSaveable { mutableStateOf(false) }
    var rewardPoints by rememberSaveable { mutableIntStateOf(0) }

    val allQuestions = remember { createAllQuestions() }

    val stageQuestions = remember(stage, restartKey) {
        // نختار 10 أسئلة جديدة قدر الإمكان، مع ضمان ظهور سؤال علم
        // وسؤال حضارة في كل مرحلة طالما توجد أسئلة غير مستخدمة من هذين النوعين.
        val seen = preferences.getStringSet("seen_questions_v2", emptySet())
            ?.toMutableSet() ?: mutableSetOf()

        fun unseenQuestions(): List<QuizQuestion> =
            allQuestions.filter { it.uniqueKey !in seen }

        var available = unseenQuestions()

        if (available.size < 10) {
            // إذا انتهى بنك الأسئلة كله نبدأ دورة جديدة فقط عند الضرورة.
            seen.clear()
            available = allQuestions
        }

        val selected = mutableListOf<QuizQuestion>()

        // سؤال علم مضمون.
        available.filter { it.category == "flag" }.shuffled().firstOrNull()?.let {
            selected += it
        }

        // سؤال حضارة مضمون.
        available.filter { it.category == "civilization" && it !in selected }
            .shuffled().firstOrNull()?.let {
                selected += it
            }

        // نكمل باقي المرحلة من الأسئلة غير المستخدمة.
        selected += available
            .filter { it !in selected }
            .shuffled()
            .take(10 - selected.size)

        val finalQuestions = selected.shuffled()

        seen.addAll(finalQuestions.map { it.uniqueKey })
        preferences.edit().putStringSet("seen_questions_v2", seen).apply()

        finalQuestions
    }

    fun resetStageStats() {
        questionIndex = 0
        score = 0
        correctCount = 0
        wrongCount = 0
        hearts = 3
        stageCompleted = false
        rewardPoints = 0
        restartKey++
    }

    fun completeStage() {
        stageCompleted = true

        if (stage < 20 && stage + 1 > unlockedStage) {
            unlockedStage = stage + 1
            preferences.edit()
                .putInt("unlocked_stage", unlockedStage)
                .apply()
        }

        if (stage in listOf(3, 6, 9, 12, 15, 18)) {
            rewardPoints = 300
            score += 300
        }

        screen = "result"
    }

    when (screen) {
        "home" -> HomeScreen(
            onStart = { screen = "stages" }
        )

        "stages" -> StageScreen(
            unlockedStage = unlockedStage,
            muted = muted,
            onMute = {
                muted = !muted

                if (muted) {
                    if (backgroundPlayer.isPlaying) {
                        backgroundPlayer.pause()
                    }
                } else {
                    if (!backgroundPlayer.isPlaying) {
                        backgroundPlayer.start()
                    }
                }
            },
            onHome = { screen = "home" },
            onStageSelected = { selectedStage ->
                if (selectedStage <= unlockedStage) {
                    stage = selectedStage
                    resetStageStats()
                    screen = "game"
                }
            }
        )

        "game" -> {
            if (stageQuestions.isEmpty() || questionIndex !in stageQuestions.indices) {
                screen = "stages"
            } else {
                GameScreen(
                    question = stageQuestions[questionIndex],
                    questionNumber = questionIndex + 1,
                    totalQuestions = stageQuestions.size,
                    hearts = hearts,
                    score = score,
                    muted = muted,
                    onAnswer = { answer, correctAnswer ->
                        val isCorrect = answer == correctAnswer

                        if (isCorrect) {
                            correctCount++
                            score += 100
                        } else {
                            wrongCount++
                            hearts--
                        }

                        if (!isCorrect && hearts <= 0) {
                            stageCompleted = false
                            screen = "result"
                        } else if (questionIndex < stageQuestions.lastIndex) {
                            questionIndex++
                        } else {
                            completeStage()
                        }
                    },
                    onSkip = {
                        wrongCount++
                        hearts--

                        if (hearts <= 0) {
                            stageCompleted = false
                            screen = "result"
                        } else if (questionIndex < stageQuestions.lastIndex) {
                            questionIndex++
                        } else {
                            completeStage()
                        }
                    },
                    onHome = { screen = "home" }
                )
            }
        }

        "result" -> ResultScreen(
            stage = stage,
            score = score,
            correctCount = correctCount,
            wrongCount = wrongCount,
            hearts = hearts,
            stageCompleted = stageCompleted,
            rewardPoints = rewardPoints,
            isFinalStage = stage == 20,
            onRetry = {
                resetStageStats()
                screen = "game"
            },
            onNextStage = {
                if (stageCompleted && stage < 20 && stage + 1 <= unlockedStage) {
                    stage++
                    resetStageStats()
                    screen = "game"
                }
            },
            onStages = { screen = "stages" },
            onHome = { screen = "home" }
        )
    }
}

@Composable
fun HomeScreen(onStart: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(20, 25, 45))
    ) {
        // صورة غلاف اللعبة من res/drawable/hazoora_cover.png
        Image(
            painter = painterResource(id = R.drawable.hazoora_cover),
            contentDescription = "Hazoora Elite Cover",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        // طبقة خفيفة حتى يبقى زر البدء واضحاً فوق الغلاف.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.10f))
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom,
            modifier = Modifier
                .fillMaxSize()
                .padding(25.dp)
        ) {
            Text(
                text = "HAZOORA ELITE",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    text = "▶ ابدأ اللعبة",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(25.dp))
        }
    }
}

@Composable
fun StageScreen(
    unlockedStage: Int,
    muted: Boolean,
    onMute: () -> Unit,
    onHome: () -> Unit,
    onStageSelected: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(20, 25, 45))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onHome) {
                Text("🏠")
            }

            Text(
                text = "المراحل",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )

            Button(onClick = onMute) {
                Text(if (muted) "🔇" else "🔊")
            }
        }

        Spacer(modifier = Modifier.height(25.dp))

        for (i in 1..20) {
            val unlocked = i <= unlockedStage

            Button(
                onClick = {
                    if (unlocked) onStageSelected(i)
                },
                enabled = unlocked,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .height(50.dp),
                shape = RoundedCornerShape(15.dp)
            ) {
                Text(
                    text = if (unlocked) "⭐ المرحلة $i" else "🔒 المرحلة $i",
                    fontSize = 18.sp
                )
            }
        }
    }
}

@Composable
fun GameScreen(
    question: QuizQuestion,
    questionNumber: Int,
    totalQuestions: Int,
    hearts: Int,
    score: Int,
    muted: Boolean,
    onAnswer: (String, String) -> Unit,
    onSkip: () -> Unit,
    onHome: () -> Unit
) {
    var timeLeft by remember(question.uniqueKey) { mutableIntStateOf(30) }
    var selectedAnswer by remember(question.uniqueKey) { mutableStateOf<String?>(null) }
    var answered by remember(question.uniqueKey) { mutableStateOf(false) }
    var showHint by remember(question.uniqueKey) { mutableStateOf(false) }
    var fiftyUsed by remember(question.uniqueKey) { mutableStateOf(false) }
    var hiddenAnswer by remember(question.uniqueKey) { mutableStateOf<String?>(null) }

    val answers = remember(question.uniqueKey) {
        listOf(question.correct, question.wrong1, question.wrong2).shuffled()
    }

    val toneGenerator = remember {
        ToneGenerator(AudioManager.STREAM_MUSIC, 80)
    }

    DisposableEffect(Unit) {
        onDispose { toneGenerator.release() }
    }

    // المؤقت: إذا انتهى الوقت تُحسب الإجابة خطأ.
    LaunchedEffect(question.uniqueKey) {
        while (timeLeft > 0 && !answered) {
            delay(1000)
            timeLeft--
        }

        if (timeLeft <= 0 && !answered) {
            answered = true

            if (!muted) {
                toneGenerator.startTone(ToneGenerator.TONE_PROP_NACK, 300)
            }

            onAnswer("__TIMEOUT__", question.correct)
        }
    }

    // عند اختيار جواب نُظهر اللون أولاً، ننتظر قليلاً، ثم ننتقل للسؤال التالي.
    LaunchedEffect(selectedAnswer) {
        val chosenAnswer = selectedAnswer

        if (chosenAnswer != null) {
            if (!muted) {
                if (chosenAnswer == question.correct) {
                    toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 250)
                } else {
                    toneGenerator.startTone(ToneGenerator.TONE_PROP_NACK, 300)
                }
            }

            delay(900)

            onAnswer(
                chosenAnswer,
                question.correct
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(20, 25, 45))
            .padding(15.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onHome) {
                Text("🏠")
            }

            Text(
                text = "❤️ $hearts",
                color = Color.White,
                fontSize = 18.sp
            )

            Text(
                text = "⭐ $score",
                color = Color.White,
                fontSize = 18.sp
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "السؤال $questionNumber من $totalQuestions",
            color = Color.White,
            fontSize = 18.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        LinearProgressIndicator(
            progress = questionNumber.toFloat() / totalQuestions.toFloat(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(25.dp))

        Text(
            text = "⏱️ $timeLeft",
            color = if (timeLeft <= 5) Color.Red else Color.White,
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        question.visual?.let { visual ->
            Text(
                text = visual,
                fontSize = 72.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        Text(
            text = question.question,
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(25.dp))

        answers.forEach { answer ->
            if (answer != hiddenAnswer) {
                val buttonColor = when {
                    selectedAnswer == answer && answer == question.correct ->
                        Color(0xFF219653)

                    selectedAnswer == answer && answer != question.correct ->
                        Color(0xFFEB5757)

                    answered && answer == question.correct ->
                        Color(0xFF219653)

                    else ->
                        Color(0xFF34405E)
                }

                Button(
                    onClick = {
                        if (!answered) {
                            // نثبت الاختيار أولاً حتى يظهر الأخضر/الأحمر.
                            selectedAnswer = answer
                            answered = true
                        }
                    },
                    enabled = !answered,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .height(55.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = buttonColor,
                        disabledContainerColor = buttonColor
                    ),
                    shape = RoundedCornerShape(15.dp)
                ) {
                    Text(
                        text = answer,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(15.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = {
                    if (!fiftyUsed && !answered) {
                        fiftyUsed = true

                        val wrongAnswers = answers.filter {
                            it != question.correct
                        }

                        if (wrongAnswers.isNotEmpty()) {
                            hiddenAnswer = wrongAnswers.random()
                        }
                    }
                },
                enabled = !fiftyUsed && !answered
            ) {
                Text("50/50")
            }

            Button(
                onClick = { showHint = true },
                enabled = !answered
            ) {
                Text("💡 تلميح")
            }

            Button(
                onClick = {
                    if (!answered) {
                        answered = true

                        if (!muted) {
                            toneGenerator.startTone(ToneGenerator.TONE_PROP_NACK, 200)
                        }

                        onSkip()
                    }
                },
                enabled = !answered
            ) {
                Text("⏭ تجاوز")
            }
        }

        if (showHint) {
            Spacer(modifier = Modifier.height(15.dp))

            Text(
                text = "💡 ${question.hint}",
                color = Color.Yellow,
                fontSize = 17.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        BannerAd()
    }
}

@Composable
fun BannerAd() {
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context ->
            AdView(context).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = "ca-app-pub-1577551070712727/7942563959"
                loadAd(AdRequest.Builder().build())
            }
        }
    )
}

@Composable
fun ResultScreen(
    stage: Int,
    score: Int,
    correctCount: Int,
    wrongCount: Int,
    hearts: Int,
    stageCompleted: Boolean,
    rewardPoints: Int,
    isFinalStage: Boolean,
    onRetry: () -> Unit,
    onNextStage: () -> Unit,
    onStages: () -> Unit,
    onHome: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(20, 25, 45))
            .padding(25.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (stageCompleted) "🏆" else "💔",
            fontSize = 75.sp
        )

        Text(
            text = if (stageCompleted) {
                if (isFinalStage) "أكملت جميع المراحل!"
                else "أكملت المرحلة $stage"
            } else {
                "انتهت المحاولات"
            },
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(25.dp))

        Text(
            text = "⭐ النقاط: $score",
            color = Color.White,
            fontSize = 20.sp
        )

        Text(
            text = "✅ الصحيح: $correctCount",
            color = Color.Green,
            fontSize = 20.sp
        )

        Text(
            text = "❌ الخطأ: $wrongCount",
            color = Color.Red,
            fontSize = 20.sp
        )

        Text(
            text = "❤️ المحاولات المتبقية: ${hearts.coerceAtLeast(0)}",
            color = Color.White,
            fontSize = 20.sp
        )

        if (rewardPoints > 0) {
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "🎁 مكافأة المرحلة: +$rewardPoints نقطة",
                color = Color.Yellow,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(30.dp))

        if (stageCompleted && !isFinalStage) {
            Button(
                onClick = onNextStage,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(55.dp)
            ) {
                Text(
                    "➡️ المرحلة التالية",
                    fontSize = 18.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
        }

        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .height(55.dp)
        ) {
            Text(
                "🔄 إعادة المرحلة",
                fontSize = 18.sp
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = onStages,
            modifier = Modifier
                .fillMaxWidth()
                .height(55.dp)
        ) {
            Text(
                "⭐ المراحل",
                fontSize = 18.sp
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = onHome,
            modifier = Modifier
                .fillMaxWidth()
                .height(55.dp)
        ) {
            Text(
                "🏠 الرئيسية",
                fontSize = 18.sp
            )
        }
    }
}

fun createAllQuestions(): List<QuizQuestion> {
    val questions = mutableListOf<QuizQuestion>()

    fun add(
        q: String,
        c: String,
        w1: String,
        w2: String,
        hint: String,
        visual: String? = null,
        category: String = "general"
    ) {
        questions.add(QuizQuestion(q, c, w1, w2, hint, visual, category))
    }

    fun addFlag(q: String, c: String, w1: String, w2: String, hint: String, visual: String) {
        add(q, c, w1, w2, hint, visual, "flag")
    }

    fun addCivilization(q: String, c: String, w1: String, w2: String, hint: String, visual: String) {
        add(q, c, w1, w2, hint, visual, "civilization")
    }

    // المرحلة 1
    add("ما هي عاصمة العراق؟", "بغداد", "البصرة", "أربيل", "هي أكبر مدن العراق.")
    add("ما هو أكبر كوكب؟", "المشتري", "الأرض", "المريخ", "هو خامس كوكب من الشمس.")
    add("كم عدد أيام الأسبوع؟", "7", "5", "10", "الأسبوع سبعة أيام.")
    add("ما لون الشمس كما نراها غالباً؟", "أصفر", "أزرق", "أخضر", "تظهر لنا بهذا اللون غالباً.")
    add("كم عدد أشهر السنة؟", "12", "10", "15", "السنة تبدأ من يناير.")
    add("ما الحيوان المعروف بملك الغابة؟", "الأسد", "الفيل", "الذئب", "حيوان مفترس مشهور.")
    add("ما الكوكب الذي نعيش عليه؟", "الأرض", "المريخ", "الزهرة", "هو الكوكب الثالث من الشمس.")
    add("ما عاصمة فرنسا؟", "باريس", "روما", "مدريد", "تشتهر ببرج إيفل.")
    add("كم عدد أرجل العنكبوت؟", "8", "6", "10", "لديه أربعة أزواج.")
    add("ما أكبر محيط؟", "المحيط الهادئ", "الأطلسي", "الهندي", "يقع بين آسيا وأمريكا.")
    add("ما الغاز الذي يحتاجه الإنسان للتنفس؟", "الأكسجين", "الهيدروجين", "الهيليوم", "ضروري للتنفس.")
    add("ما عاصمة مصر؟", "القاهرة", "الإسكندرية", "الجيزة", "تقع على نهر النيل.")
    add("كم عدد ألوان قوس قزح التقليدية؟", "7", "5", "9", "منها الأحمر والأخضر والأزرق.")
    add("ما أسرع حيوان بري؟", "الفهد", "الأسد", "الحصان", "يتميز بسرعة كبيرة جداً.")
    add("ما اسم القمر الطبيعي للأرض؟", "القمر", "الشمس", "المريخ", "يدور حول الأرض.")

    // المرحلة 2
    add("ما عاصمة اليابان؟", "طوكيو", "أوساكا", "كيوتو", "مدينة ضخمة في اليابان.")
    add("كم عدد القارات؟", "7", "5", "9", "منها آسيا وأفريقيا وأوروبا.")
    add("ما أقرب نجم إلى الأرض؟", "الشمس", "القمر", "المشتري", "هو نجم نظامنا الشمسي.")
    add("أي حيوان من التالي يعطي الحليب للإنسان عادة؟", "البقرة", "النمر", "الذئب", "من أشهر حيوانات المزارع.")
    add("ما عاصمة إيطاليا؟", "روما", "ميلانو", "نابولي", "مدينة تاريخية مشهورة.")
    add("كم عدد أصابع اليد الواحدة؟", "5", "4", "6", "منها الإبهام.")
    add("ما أكبر حيوان معروف على الأرض؟", "الحوت الأزرق", "الفيل", "الزرافة", "يعيش في المحيطات.")
    add("ما الكوكب الأحمر؟", "المريخ", "الزهرة", "زحل", "لونه يميل إلى الأحمر.")
    add("ما عاصمة السعودية؟", "الرياض", "جدة", "مكة", "تقع في وسط المملكة تقريباً.")
    add("ما اللغة الرسمية في البرازيل؟", "البرتغالية", "الإسبانية", "الإنجليزية", "البرازيل دولة في أمريكا الجنوبية.")
    add("كم دقيقة في الساعة؟", "60", "30", "100", "الساعة مقسمة إلى ستين دقيقة.")
    add("ما العنصر الذي رمزه Fe؟", "الحديد", "الذهب", "الفضة", "يدخل في صناعة الحديد والصلب.")
    add("ما عاصمة تركيا؟", "أنقرة", "إسطنبول", "إزمير", "ليست إسطنبول.")
    add("ما أكبر قارة؟", "آسيا", "أفريقيا", "أوروبا", "تضم دولاً كثيرة.")
    add("ما الجهاز الذي نستخدمه للاتصال وإجراء المكالمات؟", "الهاتف", "المصباح", "الكرسي", "يمكنه إجراء المكالمات.")

    // المرحلة 3
    add("ما عاصمة أستراليا؟", "كانبيرا", "سيدني", "ملبورن", "ليست سيدني.")
    add("كم عدد الكواكب في المجموعة الشمسية؟", "8", "9", "7", "بلوتو كوكب قزم.")
    add("ما أكبر دولة في العالم من حيث المساحة؟", "روسيا", "الصين", "كندا", "تمتد بين أوروبا وآسيا.")
    add("ما عاصمة ألمانيا؟", "برلين", "ميونخ", "هامبورغ", "مدينة أوروبية شهيرة.")
    add("ما العضو الذي يضخ الدم؟", "القلب", "الرئة", "المعدة", "عضو عضلي.")
    add("ما عاصمة الصين؟", "بكين", "شنغهاي", "هونغ كونغ", "مدينة في شمال الصين.")
    add("ما وحدة قياس الحرارة الشائعة في كثير من الدول؟", "درجة مئوية", "كيلوغرام", "متر", "تستخدم في الطقس.")
    add("ما الحيوان الذي له خرطوم؟", "الفيل", "الأسد", "الدب", "يتميز بحجمه الكبير.")
    add("ما عاصمة روسيا؟", "موسكو", "سانت بطرسبرغ", "كييف", "أكبر مدينة روسية.")
    add("ما الكوكب ذو الحلقات الشهيرة؟", "زحل", "عطارد", "المريخ", "له حلقات واضحة.")
    add("ما عاصمة إسبانيا؟", "مدريد", "برشلونة", "إشبيلية", "تقع في وسط إسبانيا.")
    add("كم ثانية في الدقيقة؟", "60", "30", "100", "الدقيقة تحتوي ستين ثانية.")
    add("ما أكبر عضو في جسم الإنسان؟", "الجلد", "القلب", "العين", "يغطي الجسم كله.")
    add("ما عاصمة كندا؟", "أوتاوا", "تورنتو", "فانكوفر", "هي العاصمة وليست أكبر مدينة.")
    add("ما مصدر الضوء الطبيعي الرئيسي للأرض؟", "الشمس", "القمر", "الماء", "نجم قريب منا.")

    // المرحلة 4
    add("ما عاصمة المملكة المتحدة؟", "لندن", "مانشستر", "ليفربول", "تقع على نهر التايمز.")
    add("ما الحيوان الذي يسمى سفينة الصحراء؟", "الجمل", "الحصان", "الفيل", "يتحمل العطش جيداً.")
    add("ما عاصمة الأردن؟", "عمّان", "العقبة", "الزرقاء", "عاصمة المملكة الأردنية الهاشمية.")
    add("ما اسم العملية التي تصنع بها النباتات غذاءها؟", "البناء الضوئي", "التنفس", "الهضم", "تحتاج إلى الضوء.")
    add("ما عاصمة اليونان؟", "أثينا", "روما", "صوفيا", "مدينة تاريخية.")
    add("ما العضوان الأساسيان المسؤولان عن التنفس؟", "الرئتان", "القلب", "المعدة", "ندخل بهما الأكسجين.")
    add("ما عاصمة الإمارات العربية المتحدة؟", "أبوظبي", "دبي", "الشارقة", "هي عاصمة الدولة.")
    add("كم عدد أيام السنة العادية؟", "365", "360", "400", "السنة الكبيسة تزيد يوماً.")
    add("ما أكبر صحراء حارة في العالم؟", "الصحراء الكبرى", "صحراء جوبي", "صحراء الربع الخالي", "تقع في أفريقيا.")
    add("ما عاصمة الهند؟", "نيودلهي", "مومباي", "بنغالور", "هي العاصمة السياسية.")
    add("من أي مادة يتكون الثلج؟", "الماء", "الحديد", "الملح", "هو الحالة الصلبة للماء.")
    add("ما عاصمة المغرب؟", "الرباط", "الدار البيضاء", "مراكش", "هي العاصمة.")
    add("ما الكوكب الأقرب إلى الشمس؟", "عطارد", "الزهرة", "الأرض", "أقرب كواكب المجموعة الشمسية للشمس.")
    add("ما عاصمة الكويت؟", "مدينة الكويت", "حولي", "الجهراء", "تحمل اسم الدولة.")
    add("ما الحيوان المعروف بطول رقبته؟", "الزرافة", "الفيل", "الأسد", "لها رقبة طويلة جداً.")

    // المرحلة 5
    add("ما عاصمة قطر؟", "الدوحة", "الريان", "الخور", "مدينة ساحلية.")
    add("ما عاصمة البحرين؟", "المنامة", "المحرق", "الرفاع", "هي العاصمة.")
    add("ما عاصمة سلطنة عُمان؟", "مسقط", "صلالة", "نزوى", "مدينة ساحلية.")
    add("ما عاصمة لبنان؟", "بيروت", "طرابلس", "صيدا", "مدينة على البحر المتوسط.")
    add("ما عاصمة سوريا؟", "دمشق", "حلب", "حمص", "من أقدم المدن المأهولة.")
    add("ما عاصمة تونس؟", "تونس", "صفاقس", "سوسة", "اسم العاصمة مثل اسم الدولة.")
    add("ما عاصمة الجزائر؟", "الجزائر", "وهران", "قسنطينة", "اسم العاصمة مثل اسم الدولة.")
    add("ما عاصمة السودان؟", "الخرطوم", "بورتسودان", "كسلا", "تقع عند التقاء النيلين الأزرق والأبيض.")
    add("ما عاصمة ليبيا؟", "طرابلس", "بنغازي", "مصراتة", "مدينة ساحلية.")
    add("ما المدينة التي يعلنها الفلسطينيون عاصمة لدولة فلسطين؟", "القدس الشرقية", "غزة", "رام الله", "ترتبط بمكانة سياسية ودينية كبيرة.")
    add("ما العاصمة الدستورية لليمن؟", "صنعاء", "عدن", "تعز", "مدينة تاريخية.")
    add("ما عاصمة موريتانيا؟", "نواكشوط", "نواذيبو", "روصو", "تقع قرب المحيط الأطلسي.")
    add("ما عاصمة الصومال؟", "مقديشو", "هرجيسا", "كيسمايو", "مدينة ساحلية.")
    add("ما عاصمة جيبوتي؟", "جيبوتي", "علي صبيح", "تاجورة", "اسمها نفس اسم الدولة.")
    add("ما عاصمة جزر القمر؟", "موروني", "فومبوني", "موتسامودو", "تقع في المحيط الهندي.")

    // المرحلة 6
    add("ما الصيغة الكيميائية للماء؟", "H2O", "CO2", "O2", "يتكون من الهيدروجين والأكسجين.")
    add("ما الغاز الذي تحتاجه النار للاحتراق؟", "الأكسجين", "النيتروجين", "الهيليوم", "موجود في الهواء.")
    add("ما مركز المجموعة الشمسية؟", "الشمس", "الأرض", "المشتري", "تدور الكواكب حولها.")
    add("ما القوة التي تجذب الأشياء نحو الأرض؟", "الجاذبية", "المغناطيسية", "الاحتكاك", "تجعل الأشياء تسقط.")
    add("ما أصغر وحدة بنائية أساسية في الكائن الحي؟", "الخلية", "العضو", "العظم", "توجد في أجسام الكائنات الحية.")
    add("ما العضو المسؤول عن التفكير؟", "الدماغ", "القلب", "الكبد", "موجود داخل الجمجمة.")
    add("ما العضو المسؤول عن ضخ الدم؟", "القلب", "الرئة", "الكلى", "ينبض باستمرار.")
    add("ما العضو الذي يساهم في هضم الطعام؟", "المعدة", "العين", "الأذن", "توجد في الجهاز الهضمي.")
    add("ما الحاسة التي تستخدم العين؟", "البصر", "السمع", "الشم", "تساعدنا على رؤية الأشياء.")
    add("ما الحاسة التي تستخدم الأذن؟", "السمع", "البصر", "التذوق", "تلتقط الأصوات.")
    add("ما الحاسة التي تستخدم الأنف؟", "الشم", "اللمس", "السمع", "تساعد على معرفة الروائح.")
    add("ما الحاسة التي تستخدم اللسان؟", "التذوق", "البصر", "السمع", "تساعد على تمييز النكهات.")
    add("ما الحاسة التي تعتمد على الجلد؟", "اللمس", "البصر", "السمع", "نشعر بها عند لمس الأشياء.")
    add("ما اسم تحول الماء السائل إلى بخار؟", "التبخر", "التجمد", "التكثف", "يحدث أسرع مع الحرارة.")
    add("ما اسم تحول بخار الماء إلى ماء سائل؟", "التكثف", "التبخر", "الانصهار", "يحدث عند انخفاض الحرارة.")

    // المرحلة 7
    add("أي دولة تشتهر ببرج إيفل؟", "فرنسا", "إيطاليا", "ألمانيا", "البرج موجود في باريس.")
    add("أي دولة تشتهر بأهرامات الجيزة؟", "مصر", "العراق", "اليونان", "تقع قرب القاهرة.")
    add("أين يقع تاج محل؟", "الهند", "الصين", "اليابان", "معلم مشهور في الهند.")
    add("أين يقع سور الصين العظيم؟", "الصين", "اليابان", "كوريا الجنوبية", "معلم صيني شهير.")
    add("في أي قارة تقع مصر؟", "أفريقيا", "أوروبا", "أستراليا", "تقع شمال شرق القارة.")
    add("في أي قارة تقع البرازيل؟", "أمريكا الجنوبية", "أفريقيا", "آسيا", "أكبر دول القارة من حيث المساحة.")
    add("في أي قارة تقع اليابان؟", "آسيا", "أوروبا", "أفريقيا", "دولة جزرية آسيوية.")
    add("في أي قارة تقع ألمانيا؟", "أوروبا", "آسيا", "أفريقيا", "دولة أوروبية.")
    add("ما البحر الذي يفصل أجزاء من أوروبا عن أفريقيا؟", "البحر المتوسط", "البحر الأحمر", "بحر العرب", "يقع بين القارتين.")
    add("ما النهر الشهير الذي يمر في مصر؟", "النيل", "دجلة", "الفرات", "من أشهر أنهار العالم.")
    add("أي نهر من التالي يجري في العراق؟", "دجلة", "النيل", "السين", "أحد نهري العراق المشهورين.")
    add("ما النهر الآخر الشهير الذي يجري في العراق؟", "الفرات", "الأمازون", "النيل", "يجري أيضاً في سوريا.")
    add("ما المحيط الواقع بين أفريقيا وأستراليا؟", "المحيط الهندي", "الأطلسي", "الهادئ", "يقع جنوب آسيا.")
    add("ما عاصمة كوريا الجنوبية؟", "سيول", "بوسان", "إنشيون", "مدينة آسيوية كبيرة.")
    add("ما عاصمة تايلاند؟", "بانكوك", "بوكيت", "شيانغ ماي", "مدينة آسيوية شهيرة.")

    // المرحلة 8
    add("ما اسم أول شهر في السنة الميلادية؟", "يناير", "مارس", "ديسمبر", "الشهر رقم 1.")
    add("ما اسم آخر شهر في السنة الميلادية؟", "ديسمبر", "نوفمبر", "أكتوبر", "الشهر رقم 12.")
    add("ما الشهر الذي يأتي بعد يناير؟", "فبراير", "مارس", "ديسمبر", "الشهر الثاني.")
    add("ما الشهر الذي يأتي قبل ديسمبر؟", "نوفمبر", "أكتوبر", "يناير", "الشهر رقم 11.")
    add("كم يوماً في الأسبوع؟", "7", "6", "8", "الأسبوع سبعة أيام.")
    add("كم ساعة في اليوم؟", "24", "12", "48", "اليوم الكامل.")
    add("كم ثانية في الساعة؟", "3600", "600", "1000", "60 دقيقة وكل دقيقة 60 ثانية.")
    add("كم دقيقة في نصف ساعة؟", "30", "20", "45", "نصف الستين.")
    add("ما اليوم الذي يأتي بعد السبت؟", "الأحد", "الاثنين", "الجمعة", "اليوم التالي للسبت.")
    add("ما اليوم الذي يأتي قبل الجمعة؟", "الخميس", "الأربعاء", "السبت", "اليوم السابق للجمعة.")
    add("ما الفصل الذي يأتي بعد الشتاء؟", "الربيع", "الصيف", "الخريف", "فصل معتدل.")
    add("ما الفصل الذي يأتي بعد الصيف؟", "الخريف", "الربيع", "الشتاء", "تسقط فيه أوراق بعض الأشجار.")
    add("ما الفصل الأكثر حرارة عادة؟", "الصيف", "الشتاء", "الخريف", "تزداد فيه درجات الحرارة.")
    add("ما الفصل الأكثر برودة عادة؟", "الشتاء", "الصيف", "الربيع", "تنخفض فيه درجات الحرارة.")
    add("ما اسم السنة التي تحتوي 366 يوماً؟", "السنة الكبيسة", "السنة القصيرة", "السنة العادية", "يضاف فيها يوم إلى فبراير.")

    // المرحلة 9
    add("ما الرياضة التي تستخدم كرة وسلة؟", "كرة السلة", "كرة القدم", "التنس", "تسجل النقاط داخل السلة.")
    add("ما الرياضة التي تستخدم مضرباً وكرة صغيرة فوق شبكة؟", "التنس", "السباحة", "الجري", "تلعب في ملعب خاص.")
    add("ما الرياضة التي تتم أساساً في الماء؟", "السباحة", "كرة القدم", "الملاكمة", "تقام في المسبح أو المياه.")
    add("كم لاعباً في فريق كرة القدم داخل الملعب عادة؟", "11", "5", "7", "يشمل العدد حارس المرمى.")
    add("ما لون بطاقة الطرد في كرة القدم؟", "الأحمر", "الأخضر", "الأزرق", "تعني خروج اللاعب.")
    add("ما الرياضة التي تستخدم القفازات في الحلبة؟", "الملاكمة", "التنس", "الجولف", "تقام في الحلبة.")
    add("ما الرياضة التي تستخدم عصا لضرب الكرة نحو الحفر؟", "الجولف", "السباحة", "الجري", "تلعب في ملعب واسع.")
    add("ما الرياضة التي تضم الجري والقفز والرمي؟", "ألعاب القوى", "الشطرنج", "كرة الماء", "تضم عدة مسابقات.")
    add("ما اللعبة التي تستخدم الملك والوزير والقلعة والحصان؟", "الشطرنج", "الدومينو", "التنس", "لعبة تفكير استراتيجية.")
    add("كم ملكاً يوجد في لعبة الشطرنج عند بداية اللعب؟", "2", "4", "1", "لكل لاعب ملك.")
    add("ما القطعة التي تتحرك بشكل حرف L في الشطرنج؟", "الحصان", "القلعة", "الوزير", "لها حركة مميزة.")
    add("ما القطعة التي تتحرك قطرياً في الشطرنج؟", "الفيل", "الحصان", "القلعة", "تتحرك على الأقطار.")
    add("ما القطعة التي تتحرك أفقياً وعمودياً في الشطرنج؟", "القلعة", "الحصان", "الفيل", "تتحرك بخط مستقيم.")
    add("ما أقوى قطعة من حيث حرية الحركة في الشطرنج عادة؟", "الوزير", "البيدق", "الحصان", "تتحرك في اتجاهات كثيرة.")
    add("ما الهدف الأساسي في الشطرنج؟", "كش ملك", "جمع النقاط", "الوصول للنهاية", "محاصرة ملك الخصم.")

    // المرحلة 10
    add("ما عاصمة الولايات المتحدة؟", "واشنطن", "نيويورك", "لوس أنجلوس", "هي العاصمة السياسية.")
    add("ما عاصمة المكسيك؟", "مكسيكو سيتي", "كانكون", "غوادالاخارا", "أكبر مدن المكسيك.")
    add("ما عاصمة الأرجنتين؟", "بوينس آيرس", "قرطبة", "مندوزا", "مدينة كبيرة في أمريكا الجنوبية.")
    add("ما عاصمة البرازيل؟", "برازيليا", "ريو دي جانيرو", "ساو باولو", "ليست ريو.")
    add("ما عاصمة النمسا؟", "فيينا", "سالزبورغ", "غراتس", "مدينة أوروبية.")
    add("ما عاصمة سويسرا الاتحادية؟", "برن", "زيورخ", "جنيف", "ليست زيورخ.")
    add("ما العاصمة الدستورية لهولندا؟", "أمستردام", "روتردام", "لاهاي", "الحكومة مقرها في لاهاي.")
    add("ما عاصمة بلجيكا؟", "بروكسل", "أنتويرب", "بروج", "مدينة أوروبية.")
    add("ما عاصمة البرتغال؟", "لشبونة", "بورتو", "فارو", "تقع قرب المحيط الأطلسي.")
    add("ما عاصمة النرويج؟", "أوسلو", "بيرغن", "تروندهايم", "مدينة شمال أوروبا.")
    add("ما عاصمة السويد؟", "ستوكهولم", "غوتنبرغ", "مالمو", "مدينة شمال أوروبية.")
    add("ما عاصمة فنلندا؟", "هلسنكي", "توركو", "تامبيري", "تقع قرب بحر البلطيق.")
    add("ما عاصمة الدنمارك؟", "كوبنهاغن", "آرهوس", "أودنسه", "مدينة أوروبية شمالية.")
    add("ما عاصمة أيرلندا؟", "دبلن", "كورك", "غالواي", "تقع على الساحل الشرقي.")
    add("ما عاصمة نيوزيلندا؟", "ويلينغتون", "أوكلاند", "كرايستشيرش", "تقع في الجزيرة الشمالية.")

    // أسئلة الأعلام والحضارات الجديدة
    addFlag("هذا علم أي دولة؟", "العراق", "الأردن", "سوريا", "يحتوي على الأحمر والأبيض والأسود وعبارة الله أكبر.", "🇮🇶")
    addFlag("هذا علم أي دولة؟", "اليابان", "الصين", "كوريا الجنوبية", "دائرة حمراء على خلفية بيضاء.", "🇯🇵")
    addFlag("هذا علم أي دولة؟", "فرنسا", "إيطاليا", "هولندا", "ثلاثة أشرطة عمودية: أزرق وأبيض وأحمر.", "🇫🇷")
    addFlag("هذا علم أي دولة؟", "ألمانيا", "بلجيكا", "إسبانيا", "ألوانه أسود وأحمر وذهبي.", "🇩🇪")
    addFlag("هذا علم أي دولة؟", "البرازيل", "الأرجنتين", "المكسيك", "أخضر وفي وسطه معين أصفر ودائرة زرقاء.", "🇧🇷")
    addFlag("هذا علم أي دولة؟", "كندا", "الولايات المتحدة", "أستراليا", "تتوسطه ورقة قيقب حمراء.", "🇨🇦")
    addFlag("هذا علم أي دولة؟", "إيطاليا", "فرنسا", "المكسيك", "أخضر وأبيض وأحمر بشكل عمودي.", "🇮🇹")
    addFlag("هذا علم أي دولة؟", "إسبانيا", "البرتغال", "رومانيا", "أحمر وأصفر وأحمر.", "🇪🇸")
    addFlag("هذا علم أي دولة؟", "تركيا", "تونس", "باكستان", "أحمر وعليه هلال ونجمة بيضاء.", "🇹🇷")
    addFlag("هذا علم أي دولة؟", "السعودية", "الإمارات", "عُمان", "أخضر وعليه الشهادة وسيف.", "🇸🇦")
    addFlag("هذا علم أي دولة؟", "الإمارات", "الكويت", "الأردن", "أحمر وأخضر وأبيض وأسود.", "🇦🇪")
    addFlag("هذا علم أي دولة؟", "مصر", "العراق", "اليمن", "أحمر وأبيض وأسود ويتوسطه النسر.", "🇪🇬")
    addFlag("هذا علم أي دولة؟", "المملكة المتحدة", "أستراليا", "نيوزيلندا", "يعرف باسم Union Jack.", "🇬🇧")
    addFlag("هذا علم أي دولة؟", "الولايات المتحدة", "ليبيريا", "ماليزيا", "نجوم بيضاء وخطوط حمراء وبيضاء.", "🇺🇸")
    addFlag("هذا علم أي دولة؟", "الصين", "فيتنام", "سنغافورة", "أحمر وعليه نجوم صفراء.", "🇨🇳")
    addFlag("هذا علم أي دولة؟", "الهند", "إيرلندا", "النيجر", "برتقالي وأبيض وأخضر مع عجلة زرقاء.", "🇮🇳")
    addFlag("هذا علم أي دولة؟", "كوريا الجنوبية", "اليابان", "كوريا الشمالية", "أبيض وفي الوسط رمز أحمر وأزرق.", "🇰🇷")
    addFlag("هذا علم أي دولة؟", "الأرجنتين", "أوروغواي", "اليونان", "أزرق فاتح وأبيض وفيه شمس.", "🇦🇷")
    addFlag("هذا علم أي دولة؟", "المكسيك", "إيطاليا", "بلغاريا", "أخضر وأبيض وأحمر مع شعار في الوسط.", "🇲🇽")
    addFlag("هذا علم أي دولة؟", "البرتغال", "إسبانيا", "المغرب", "أخضر وأحمر مع شعار قرب الوسط.", "🇵🇹")
    addFlag("هذا علم أي دولة؟", "اليونان", "فنلندا", "إسرائيل", "أزرق وأبيض بخطوط وصليب.", "🇬🇷")
    addFlag("هذا علم أي دولة؟", "السويد", "فنلندا", "النرويج", "أزرق وصليب أصفر.", "🇸🇪")
    addFlag("هذا علم أي دولة؟", "النرويج", "الدنمارك", "آيسلندا", "أحمر وصليب أزرق محاط بالأبيض.", "🇳🇴")
    addFlag("هذا علم أي دولة؟", "سويسرا", "الدنمارك", "النمسا", "أحمر ويتوسطه صليب أبيض.", "🇨🇭")
    addFlag("هذا علم أي دولة؟", "أستراليا", "نيوزيلندا", "المملكة المتحدة", "أزرق وعليه نجوم وUnion Jack.", "🇦🇺")
    addCivilization("أي حضارة اشتهرت بالكتابة المسمارية؟", "حضارة بلاد الرافدين", "الحضارة الرومانية", "حضارة المايا", "ظهرت في بلاد الرافدين.", "🏺")
    addCivilization("أي حضارة ارتبطت بمدينة أثينا؟", "الحضارة اليونانية", "الحضارة الفرعونية", "الحضارة السومرية", "أثينا مدينة يونانية قديمة.", "🏛️")
    addCivilization("أي حضارة بنت الأهرامات في الجيزة؟", "الحضارة المصرية القديمة", "الحضارة الرومانية", "الحضارة الفارسية", "الأهرامات من أشهر آثار مصر القديمة.", "🏺")
    addCivilization("أي حضارة ارتبطت بالكولوسيوم؟", "الحضارة الرومانية", "الحضارة البابلية", "الحضارة الهندية", "الكولوسيوم في روما.", "🏛️")
    addCivilization("أي حضارة ازدهرت في أمريكا الوسطى وبنت مدناً هرمية؟", "حضارة المايا", "الحضارة الفينيقية", "الحضارة الآشورية", "من حضارات أمريكا الوسطى.", "🗿")
    addCivilization("مدينة بابل تنتمي إلى أي حضارة؟", "الحضارة البابلية", "الحضارة اليونانية", "الحضارة الصينية", "بابل مدينة عراقية قديمة.", "🏺")
    addCivilization("من بنى مدينة أور القديمة؟", "السومريون", "الرومان", "المايا", "أور من أشهر مدن سومر.", "🏺")
    addCivilization("أي حضارة اتخذت نينوى عاصمة مشهورة لها؟", "الآشوريون", "الفينيقيون", "الإغريق", "نينوى ارتبطت بالإمبراطورية الآشورية.", "🛡️")
    addCivilization("أي حضارة اشتهرت بالملاحة والتجارة في البحر المتوسط؟", "الفينيقيون", "السومريون", "الإنكا", "مدنهم الساحلية اشتهرت بالتجارة.", "⛵")
    addCivilization("شريعة حمورابي ترتبط بأي حضارة؟", "الحضارة البابلية", "الحضارة الرومانية", "الحضارة اليابانية", "حمورابي ملك بابلي.", "🏺")
    addCivilization("ما اسم الكتابة التي استخدمها المِصريون القدماء؟", "الهيروغليفية", "المسمارية", "اللاتينية", "تظهر على المعابد والمقابر المصرية.", "📜")
    addCivilization("ما اسم الكتابة التي استخدمها السومريون؟", "المسمارية", "الهيروغليفية", "العربية", "كانت تكتب على ألواح الطين.", "🏺")
    addCivilization("من ارتبطت بهم الألعاب الأولمبية القديمة؟", "الإغريق", "البابليون", "الآشوريون", "بدأت الألعاب الأولمبية في اليونان القديمة.", "🏛️")
    addCivilization("مدينة سبارتا تنتمي إلى أي حضارة؟", "الحضارة اليونانية", "الحضارة المصرية", "الحضارة الفارسية", "سبارتا مدينة يونانية قديمة.", "⚔️")
    addCivilization("أي حضارة اشتهرت بمجلس الشيوخ في روما؟", "الحضارة الرومانية", "الحضارة السومرية", "الحضارة المايا", "مجلس الشيوخ كان مؤسسة رومانية.", "🏛️")
    addCivilization("حدائق بابل المعلقة ترتبط تقليدياً بأي حضارة؟", "الحضارة البابلية", "الحضارة اليونانية", "الحضارة الصينية", "تنسب إلى مدينة بابل.", "🏺")
    addCivilization("سور الصين العظيم يرتبط بأي حضارة؟", "الحضارة الصينية", "الحضارة الرومانية", "الحضارة الفرعونية", "يقع في الصين.", "🧱")
    addCivilization("حضارة الإنكا ازدهرت في أي قارة؟", "أمريكا الجنوبية", "أفريقيا", "أوروبا", "كانت في منطقة جبال الأنديز.", "🗿")
    addCivilization("أي حضارة اشتهرت في المكسيك الوسطى قبل الإسبان؟", "الأزتك", "الرومان", "الآشوريون", "عاصمتهم كانت تينوتشتيتلان.", "🌽")
    addCivilization("أي حضارة قديمة ازدهرت قرب نهري دجلة والفرات؟", "حضارات بلاد الرافدين", "حضارة الإنكا", "الحضارة اليابانية", "المنطقة تعرف ببلاد ما بين النهرين.", "🏺")
    addCivilization("الفيلسوف سقراط ينتمي إلى أي حضارة؟", "الحضارة اليونانية", "الحضارة البابلية", "الحضارة الهندية", "عاش في أثينا.", "🏛️")
    addCivilization("يوليوس قيصر شخصية من أي حضارة؟", "الحضارة الرومانية", "الحضارة المصرية", "حضارة المايا", "كان قائداً وسياسياً رومانيًا.", "🏛️")
    addCivilization("الفرعون كان لقب حاكم أي حضارة؟", "الحضارة المصرية القديمة", "الحضارة السومرية", "الحضارة الفينيقية", "لقب حكام مصر القديمة.", "🏺")
    addCivilization("أي حضارة عاشت في جبال الأنديز وبنت ماتشو بيتشو؟", "الإنكا", "المايا", "الفينيقيون", "ماتشو بيتشو في بيرو.", "🛶")
    addCivilization("أي حضارة عراقية قديمة ارتبطت ببوابة عشتار؟", "الحضارة البابلية", "الحضارة الرومانية", "الحضارة اليونانية", "بوابة عشتار من آثار بابل.", "🏺")

    return questions
}
