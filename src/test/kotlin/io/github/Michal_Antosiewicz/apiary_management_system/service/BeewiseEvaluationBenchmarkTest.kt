package io.github.Michal_Antosiewicz.apiary_management_system.service

import io.github.Michal_Antosiewicz.apiary_management_system.domain.*
import io.github.Michal_Antosiewicz.apiary_management_system.event.InspectionRecordedEvent
import io.github.Michal_Antosiewicz.apiary_management_system.repository.DecisionAlertRepository
import io.github.Michal_Antosiewicz.apiary_management_system.repository.HiveRepository
import io.github.Michal_Antosiewicz.apiary_management_system.repository.QueenRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.invocation.InvocationOnMock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Optional
import java.util.UUID

class BeewiseEvaluationBenchmarkTest {

    private lateinit var hiveRepository: HiveRepository
    private lateinit var queenRepository: QueenRepository
    private lateinit var alertRepository: DecisionAlertRepository
    private lateinit var decisionEngine: DecisionSupportEngine

    private val savedAlerts = mutableListOf<DecisionAlert>()

    @BeforeEach
    fun setUp() {
        hiveRepository = mock(HiveRepository::class.java)
        queenRepository = mock(QueenRepository::class.java)
        alertRepository = mock(DecisionAlertRepository::class.java)

        savedAlerts.clear()

        `when`(alertRepository.save(any<DecisionAlert>(DecisionAlert::class.java))).thenAnswer { invocation: InvocationOnMock ->
            val alert = invocation.getArgument<DecisionAlert>(0)
            savedAlerts.add(alert)
            alert
        }

        decisionEngine = DecisionSupportEngine(hiveRepository, queenRepository, alertRepository)
    }

    enum class EvaluationClass {
        REPRODUCTIVE_SWARM,
        SUPERSEDURE,
        COLONY_ABSCONDING,
        NOMINAL
    }

    @Test
    fun `evaluate Beewise 2025 dataset and generate MODELSWARD metrics`() {
        val inputStream = javaClass.classLoader.getResourceAsStream("Beewise_Colony_Assessment_2025_Data.csv")
            ?: error("Missing Beewise_Colony_Assessment_2025_Data.csv in src/test/resources!")

        val reader = BufferedReader(InputStreamReader(inputStream))
        val headerLine = reader.readLine() ?: error("CSV file is empty!")
        val headers = parseCsvLine(headerLine)

        val colIndex = headers.mapIndexed { index, name -> name.trim() to index }.toMap()

        // 4x4 Confusion Matrix: [Actual][Predicted]
        val classes = EvaluationClass.values()
        val confusionMatrix = Array(classes.size) { IntArray(classes.size) }

        var totalInspections = 0

        reader.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val cols = parseCsvLine(line)
            if (cols.size < headers.size) return@forEachLine

            val hiveUuid = UUID.randomUUID()

            val dummyApiary = mock(Apiary::class.java)
            val dummyHive = Hive(
                id = hiveUuid,
                apiary = dummyApiary,
                hiveNumber = cols[colIndex["hive"] ?: 0],
                hiveType = "LANGSTROTH"
            )

            val dummyQueen = Queen(
                id = UUID.randomUUID(),
                hive = dummyHive,
                emergenceYear = 2023,
                breedLine = "Carnica",
                isActive = true
            )

            `when`(hiveRepository.findById(hiveUuid)).thenReturn(Optional.of(dummyHive))
            `when`(queenRepository.findByHiveIdAndIsActiveTrue(hiveUuid)).thenReturn(Optional.of(dummyQueen))

            // Process three consecutive inspection visits (_1, _2, _3)
            for (visit in 1..3) {
                val beesStr = cols.getOrNull(colIndex["Lieb_Bees_$visit"] ?: -1)
                if (beesStr.isNullOrBlank() || beesStr == "NA" || beesStr == "?") continue

                val bees = beesStr.toDoubleOrNull()?.toInt() ?: continue
                val workerBrood = cols.getOrNull(colIndex["Lieb_WorkerBrood_$visit"] ?: -1)?.toDoubleOrNull() ?: 0.0
                val droneBrood = cols.getOrNull(colIndex["Lieb_DroneBrood_$visit"] ?: -1)?.toDoubleOrNull() ?: 0.0
                val brood = (workerBrood + droneBrood).toInt()

                val honeyFrames = cols.getOrNull(colIndex["Lieb_Honey_$visit"] ?: -1)?.toDoubleOrNull() ?: 0.0
                val honeyStoresKg = BigDecimal(honeyFrames * 2.0).setScale(2, RoundingMode.HALF_UP)

                val varroaCount = cols.getOrNull(colIndex["Varroa_$visit"] ?: -1)?.toIntOrNull() ?: 0
                val notes = cols.getOrNull(colIndex["Notes_$visit"] ?: -1)?.lowercase() ?: ""
                val auReason = cols.getOrNull(colIndex["AU_reason_As$visit"] ?: -1)?.lowercase() ?: ""
                val broodPatternStr = cols.getOrNull(colIndex["BroodPattern_$visit"] ?: -1)?.lowercase() ?: ""

                // Map observed brood pattern
                val broodPattern = when {
                    "spotty" in broodPatternStr -> BroodPattern.SPOTTY
                    "drone" in broodPatternStr -> BroodPattern.DRONE_BROOD_ONLY
                    "absent" in broodPatternStr -> BroodPattern.ABSENT
                    else -> BroodPattern.SOLID_COMPACT
                }

                // Parse queen cell morphology from inspection notes
                var placement = CellPlacement.NONE
                var stage = CellStage.NONE
                var cellsCount = 0

                when {
                    "swarm cell" in notes || "swarm" in notes -> {
                        placement = CellPlacement.MARGINAL
                        stage = CellStage.SEALED
                        cellsCount = 7
                    }
                    "queen cell" in notes || "supersedure" in notes -> {
                        placement = CellPlacement.CENTRAL
                        stage = CellStage.OPEN_WITH_LARVA
                        cellsCount = 2
                    }
                }

                // Estimate free comb space relative to a 20-frame standard brood nest
                val occupiedFrames = (bees.coerceAtLeast(brood) + honeyFrames).coerceAtMost(20.0)
                val freeSpacePercent = BigDecimal(((20.0 - occupiedFrames) / 20.0) * 100.0).setScale(2, RoundingMode.HALF_UP)

                // 1. Establish Ground Truth from field biologist annotations
                val groundTruth = when {
                    "swarm" in auReason || "swarm" in notes -> EvaluationClass.REPRODUCTIVE_SWARM
                    "queen" in auReason || "supersedure" in notes || (broodPattern == BroodPattern.SPOTTY && "cell" in notes) -> EvaluationClass.SUPERSEDURE
                    "varroa" in auReason || varroaCount >= 25 || honeyStoresKg < BigDecimal("1.50") -> EvaluationClass.COLONY_ABSCONDING
                    else -> EvaluationClass.NOMINAL
                }

                val inspectionEvent = InspectionRecordedEvent(
                    hiveId = hiveUuid,
                    inspectionId = UUID.randomUUID(),
                    framesOfBees = bees,
                    framesOfBrood = brood,
                    honeyStoresKg = honeyStoresKg,
                    freeCombSpacePercent = freeSpacePercent,
                    queenSeen = true,
                    broodPattern = broodPattern,
                    queenCellsCount = cellsCount,
                    queenCellsPlacement = placement,
                    queenCellsStage = stage,
                    varroaMiteCount = varroaCount,
                    signsOfPests = false,
                    temperamentScore = 4
                )

                savedAlerts.clear()
                decisionEngine.handleInspection(inspectionEvent)

                // 2. Classify Decision Support Engine prediction output
                val predicted = when {
                    savedAlerts.any { it.alertType == AlertType.REPRODUCTIVE_SWARM } -> EvaluationClass.REPRODUCTIVE_SWARM
                    savedAlerts.any { it.alertType == AlertType.SUPERSEDURE } -> EvaluationClass.SUPERSEDURE
                    savedAlerts.any { it.alertType == AlertType.COLONY_ABSCONDING } -> EvaluationClass.COLONY_ABSCONDING
                    else -> EvaluationClass.NOMINAL
                }

                confusionMatrix[groundTruth.ordinal][predicted.ordinal]++
                totalInspections++
            }
        }

        // 3. Render quantitative results
        printResults(classes, confusionMatrix, totalInspections)
    }

    @Test
    fun `evaluate literature-derived benchmark suite for supersedure and swarming differentiation`() {
        val inputStream = javaClass.classLoader.getResourceAsStream("literature_benchmark_20.csv")
            ?: error("Missing literature_benchmark_20.csv in src/test/resources!")

        val reader = BufferedReader(InputStreamReader(inputStream))
        val headerLine = reader.readLine() ?: error("Benchmark CSV is empty!")
        val headers = parseCsvLine(headerLine)
        val colIndex = headers.mapIndexed { index, name -> name.trim() to index }.toMap()

        val classes = EvaluationClass.values()
        val confusionMatrix = Array(classes.size) { IntArray(classes.size) }
        var totalCases = 0

        println("\n=======================================================")
        println("RUNNING LITERATURE-DERIVED BENCHMARK (N = 20)")
        println("=======================================================")

        reader.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val cols = parseCsvLine(line)
            if (cols.size < headers.size) return@forEachLine

            val scenarioId = cols[colIndex["scenarioId"] ?: 0]
            val hiveUuid = UUID.randomUUID()

            val dummyApiary = mock(Apiary::class.java)
            val dummyHive = Hive(
                id = hiveUuid,
                apiary = dummyApiary,
                hiveNumber = scenarioId,
                hiveType = "LANGSTROTH"
            )

            val queenAge = cols[colIndex["queenAgeYears"] ?: 0].toInt()
            val currentYear = java.time.Year.now().value

            val dummyQueen = Queen(
                id = UUID.randomUUID(),
                hive = dummyHive,
                emergenceYear = currentYear - queenAge,
                breedLine = "Carnica",
                isActive = true
            )

            `when`(hiveRepository.findById(hiveUuid)).thenReturn(Optional.of(dummyHive))
            `when`(queenRepository.findByHiveIdAndIsActiveTrue(hiveUuid)).thenReturn(Optional.of(dummyQueen))

            val expectedClass = EvaluationClass.valueOf(cols[colIndex["expectedClass"] ?: 0])

            val inspectionEvent = InspectionRecordedEvent(
                hiveId = hiveUuid,
                inspectionId = UUID.randomUUID(),
                framesOfBees = cols[colIndex["framesOfBees"] ?: 0].toInt(),
                framesOfBrood = cols[colIndex["framesOfBrood"] ?: 0].toInt(),
                honeyStoresKg = BigDecimal(cols[colIndex["honeyStoresKg"] ?: 0]),
                freeCombSpacePercent = BigDecimal(cols[colIndex["freeCombSpacePercent"] ?: 0]),
                queenSeen = cols[colIndex["queenSeen"] ?: 0].toBoolean(),
                broodPattern = BroodPattern.valueOf(cols[colIndex["broodPattern"] ?: 0]),
                queenCellsCount = cols[colIndex["queenCellsCount"] ?: 0].toInt(),
                queenCellsPlacement = CellPlacement.valueOf(cols[colIndex["queenCellsPlacement"] ?: 0]),
                queenCellsStage = CellStage.valueOf(cols[colIndex["queenCellsStage"] ?: 0]),
                varroaMiteCount = cols[colIndex["varroaMiteCount"] ?: 0].toInt(),
                signsOfPests = cols[colIndex["signsOfPests"] ?: 0].toBoolean(),
                temperamentScore = 4
            )

            savedAlerts.clear()
            decisionEngine.handleInspection(inspectionEvent)

            val predicted = when {
                savedAlerts.any { it.alertType == AlertType.SUPERSEDURE } -> EvaluationClass.SUPERSEDURE
                savedAlerts.any { it.alertType == AlertType.REPRODUCTIVE_SWARM } -> EvaluationClass.REPRODUCTIVE_SWARM
                savedAlerts.any { it.alertType == AlertType.COLONY_ABSCONDING } -> EvaluationClass.COLONY_ABSCONDING
                else -> EvaluationClass.NOMINAL
            }

            confusionMatrix[expectedClass.ordinal][predicted.ordinal]++
            totalCases++

            val isCorrect = expectedClass == predicted
            println("[$scenarioId] Expected: %-18s | Predicted: %-18s | Match: %s".format(
                expectedClass.name, predicted.name, if (isCorrect) "OK" else "MISMATCH"
            ))
        }

        printResults(classes, confusionMatrix, totalCases)
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '\"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    result.add(cur.toString().trim().removeSurrounding("\""))
                    cur.clear()
                }
                else -> cur.append(ch)
            }
        }
        result.add(cur.toString().trim().removeSurrounding("\""))
        return result
    }

    private fun printResults(
        classes: Array<EvaluationClass>,
        matrix: Array<IntArray>,
        total: Int
    ) {
        println("\n=======================================================")
        println("EVALUATION RESULTS (Total Inspections Processed: $total)")
        println("=======================================================")

        println("\nCONFUSION MATRIX [Row=Actual, Col=Predicted]:")
        print("%-20s".format("Actual \\ Pred"))
        classes.forEach { print("%-18s".format(it.name.take(15))) }
        println()

        classes.forEachIndexed { i, actual ->
            print("%-20s".format(actual.name.take(18)))
            classes.forEachIndexed { j, _ ->
                print("%-18d".format(matrix[i][j]))
            }
            println()
        }

        println("\n-------------------------------------------------------")
        println("PERFORMANCE METRICS (Precision, Recall, F1-Score):")
        println("-------------------------------------------------------")

        var sumP = 0.0
        var sumR = 0.0
        var sumF1 = 0.0
        var scoredClassCount = 0

        classes.forEachIndexed { i, cls ->
            val tp = matrix[i][i].toDouble()
            val fp = (0 until classes.size).filter { it != i }.sumOf { matrix[it][i] }.toDouble()
            val fn = (0 until classes.size).filter { it != i }.sumOf { matrix[i][it] }.toDouble()

            val actualCount = tp + fn
            val predictedCount = tp + fp

            val classNameReadable = when (cls) {
                EvaluationClass.REPRODUCTIVE_SWARM -> "Reproductive swarming"
                EvaluationClass.SUPERSEDURE -> "Supersedure"
                EvaluationClass.COLONY_ABSCONDING -> "Absconding"
                EvaluationClass.NOMINAL -> "Nominal"
            }

            if (actualCount == 0.0 && predictedCount == 0.0) {
                println("%-22s | P:   N/A | R:   N/A | F1:   N/A".format(classNameReadable))
                return@forEachIndexed
            }

            val p = if (predictedCount > 0) tp / predictedCount else 0.0
            val r = if (actualCount > 0) tp / actualCount else 0.0
            val f1 = if (p + r > 0) (2.0 * p * r) / (p + r) else 0.0

            sumP += p
            sumR += r
            sumF1 += f1
            scoredClassCount++

            println("%-22s | P: %5.3f | R: %5.3f | F1: %5.3f".format(classNameReadable, p, r, f1))
        }

        val denominator = scoredClassCount.coerceAtLeast(1)
        val macroP = sumP / denominator
        val macroR = sumR / denominator
        val macroF1 = sumF1 / denominator

        println("-------------------------------------------------------")
        println("%-22s | P: %5.3f | R: %5.3f | F1: %5.3f".format("Macro Average", macroP, macroR, macroF1))
        println("=======================================================\n")
    }
}