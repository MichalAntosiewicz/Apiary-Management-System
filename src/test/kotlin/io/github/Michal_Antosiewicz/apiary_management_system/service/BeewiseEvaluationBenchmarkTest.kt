package io.github.Michal_Antosiewicz.apiary_management_system.service

import io.github.Michal_Antosiewicz.apiary_management_system.domain.*
import io.github.Michal_Antosiewicz.apiary_management_system.event.InspectionRecordedEvent
import io.github.Michal_Antosiewicz.apiary_management_system.repository.DecisionAlertRepository
import io.github.Michal_Antosiewicz.apiary_management_system.repository.HiveRepository
import io.github.Michal_Antosiewicz.apiary_management_system.repository.QueenRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/**
 * Cross-language regression of computational experiments, not field validation.
 * Fixtures come from research/analyze_beewise.py and immutable upstream 41521e6.
 * Expected predictions are program outputs, not independent biological diagnoses.
 * NONE/0 in ablations disables a feature contribution; it is not an observation.
 * Brood states and ages in the counterfactual grid are hypotheses, not imputations.
 */
class BeewiseEvaluationBenchmarkTest {
    private val thresholds = mapOf(
        "REPRODUCTIVE_SWARM" to BigDecimal("0.450"),
        "SUPERSEDURE" to BigDecimal("0.550"),
        "COLONY_ABSCONDING" to BigDecimal("0.550")
    )

    @Test
    fun `field-data representation experiments match independent Python transcription`() {
        val cases = fixtures().filter { it.getValue("variant") != "author_scenarios" }
        assertEquals(6672, cases.size, "Fixture coverage changed; regenerate and review the experiment")
        val counts = cases.groupingBy { it.getValue("variant") }.eachCount()
        assertEquals(359, counts["legacy_note_features"])
        assertEquals(359, counts["no_note_features"])
        assertEquals(306, counts["no_note_numeric_complete"])
        for (pattern in BroodPattern.entries) {
            assertEquals(353, counts["brood_agnostic_complete__${pattern.name}"])
            for (age in 0..2) assertEquals(353, counts["age_brood_grid__age_${age}__${pattern.name}"])
        }
        cases.forEach(::verifyFixture)
        println("Computational concordance: ${cases.size} field-data evaluations matched Python predictions and emitted scores.")
        println("Representation experiments use proxy labels and counterfactual inputs; they are not biological validation.")
    }

    @Test
    fun `author scenarios retain documented SUP-08 disagreement`() {
        val cases = fixtures().filter { it.getValue("variant") == "author_scenarios" }
        assertEquals(20, cases.size)
        cases.forEach(::verifyFixture)
        val disagreements = cases.filter { it.getValue("expected_prediction") != it.getValue("proxy_or_scenario_label") }
        assertEquals(listOf("SUP-08"), disagreements.map { it.getValue("case_id") })
        assertEquals("NOMINAL", disagreements.single().getValue("expected_prediction"))
        println("Author-defined scenarios: 19/20 label agreements; SUP-08 remains a recorded specification disagreement.")
    }

    private fun verifyFixture(row: Map<String, String>) {
        val context = "${row.getValue("variant")}/${row.getValue("case_id")}"
        val hiveRepository = mock(HiveRepository::class.java)
        val queenRepository = mock(QueenRepository::class.java)
        val alertRepository = mock(DecisionAlertRepository::class.java)
        val alerts = mutableListOf<DecisionAlert>()
        `when`(alertRepository.save(any(DecisionAlert::class.java))).thenAnswer {
            it.getArgument<DecisionAlert>(0).also(alerts::add)
        }
        val hiveId = UUID.nameUUIDFromBytes(context.toByteArray(Charsets.UTF_8))
        val hive = Hive(id = hiveId, apiary = mock(Apiary::class.java), hiveNumber = row.getValue("case_id"), hiveType = "LANGSTROTH")
        val date = LocalDate.parse(row.getValue("inspection_date"))
        val age = row.getValue("queen_age_assumed").toInt()
        val queen = Queen(
            id = UUID.nameUUIDFromBytes("queen/$context".toByteArray(Charsets.UTF_8)),
            hive = hive, emergenceYear = date.year - age, breedLine = "Unspecified test assumption", isActive = true
        )
        `when`(hiveRepository.findById(hiveId)).thenReturn(Optional.of(hive))
        `when`(queenRepository.findByHiveIdAndIsActiveTrue(hiveId)).thenReturn(Optional.of(queen))
        val event = InspectionRecordedEvent(
            eventId = UUID.nameUUIDFromBytes("event/$context".toByteArray(Charsets.UTF_8)),
            hiveId = hiveId, occurredAt = date.atStartOfDay().toInstant(ZoneOffset.UTC),
            inspectionId = UUID.nameUUIDFromBytes("inspection/$context".toByteArray(Charsets.UTF_8)),
            framesOfBees = row.getValue("frames_of_bees").toInt(),
            framesOfBrood = row.getValue("frames_of_brood").toInt(),
            honeyStoresKg = BigDecimal(row.getValue("honey_kg")),
            freeCombSpacePercent = BigDecimal(row.getValue("free_space_percent")),
            queenSeen = true, // unused scorer input, preserved adapter assumption
            broodPattern = BroodPattern.valueOf(row.getValue("brood_pattern")),
            queenCellsCount = row.getValue("queen_cells_count").toInt(),
            queenCellsPlacement = CellPlacement.valueOf(row.getValue("queen_cells_placement")),
            queenCellsStage = CellStage.valueOf(row.getValue("queen_cells_stage")),
            varroaMiteCount = row.getValue("varroa_count").toInt(),
            signsOfPests = row.getValue("signs_of_pests").toBooleanStrict(), temperamentScore = 4
        )
        DecisionSupportEngine(hiveRepository, queenRepository, alertRepository).handleInspection(event)
        val expectedScores = mapOf(
            "REPRODUCTIVE_SWARM" to BigDecimal(row.getValue("swarming_score")),
            "SUPERSEDURE" to BigDecimal(row.getValue("supersedure_score")),
            "COLONY_ABSCONDING" to BigDecimal(row.getValue("absconding_score"))
        )
        val expectedEmitted = expectedScores.filter { (name, score) -> score >= thresholds.getValue(name) }
        assertEquals(expectedEmitted.keys, alerts.map { it.alertType.name }.toSet(), "$context emitted alert types")
        assertEquals(expectedEmitted.size, alerts.size, "$context duplicate alerts")
        for (alert in alerts) {
            assertEquals(0, expectedEmitted.getValue(alert.alertType.name).compareTo(alert.riskScore), "$context emitted score for ${alert.alertType}")
        }
        val selected = row.getValue("priority").split(';').firstOrNull { name -> alerts.any { it.alertType.name == name } } ?: "NOMINAL"
        assertEquals(row.getValue("expected_prediction"), selected, "$context selected alert class")
    }

    private fun fixtures(): List<Map<String, String>> {
        val stream = javaClass.classLoader.getResourceAsStream("jvm_cases.csv")
            ?: error("Missing jvm_cases.csv: regenerate with research/analyze_beewise.py")
        return stream.bufferedReader(Charsets.UTF_8).use { reader ->
            val headers = requireNotNull(reader.readLine()).split(',')
            reader.lineSequence().filter { it.isNotBlank() }.map { line ->
                // Generated fields are identifiers, enums, ISO dates and numbers only.
                // Notes with possible commas are exported separately in research/outputs.
                assertTrue('"' !in line, "Unexpected quoted fixture field")
                val values = line.split(',')
                assertEquals(headers.size, values.size, "Malformed fixture row")
                headers.zip(values).toMap()
            }.toList()
        }
    }
}
