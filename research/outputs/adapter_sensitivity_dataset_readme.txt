BEEWISE COLONY ASSESSMENT 2025 DATASET

DATASET DESCRIPTION
===================

This dataset contains honey bee colony assessment data collected during a field study evaluating an automated heat treatment system (Beewise Heat Chamber™) and colonies receiving VarroxSan® (oxalic acid extended-release treatment) for control of Varroa destructor mites in honey bee colonies compared to untreated control colonies.

Data were collected from honey bee colonies housed in BeeHome™ systems in the Central Valley of California following almond pollination. Colonies were monitored from March through May 2025.

The dataset contains colony-level observations from 120 colonies and includes colony identification information, treatment assignment, colony condition, Varroa mite infestation, brood, adult bee population, honey, pollen, queen status, and observations related to queen issues and swarming.


FILES
=====

Beewise_Colony_Assessment_2025_Data.csv
    Colony-level assessment dataset associated with the study.


EXPERIMENTAL TREATMENTS
=======================

Colonies were assigned to one of three treatment groups:

    Heat
        Treatment using the Beewise Heat Chamber™.

    Chemical
        Treatment with VarroxSan®, an extended-release oxalic acid treatment.

    Control
        Untreated colonies.


ASSESSMENT DATES
================

The primary colony assessments included in the dataset were:

    Assessment 1    March 24, 2025
    Assessment 2    April 28, 2025
    Assessment 3    May 20, 2025

Additional queen-status observations were recorded during April 2025.

Assessment 1 represents the pre-treatment assessment. Assessments 2 and 3 represent post-treatment assessment points used in analyses of treatment effects.


VARIABLES
=========

The dataset contains the following general categories of variables.


COLONY IDENTIFICATION AND EXPERIMENTAL INFORMATION
---------------------------------------------------

    Treatment
        Experimental treatment assigned to the colony.

    Beehome
        BeeHome™ identification number.

    Beehome_side
        BeeHome™ side identification.

    Hive Id
        Colony/hive identification number.

    Colony
        Colony identification.

    Block
        Experimental block.

    hive
        Combined hive identifier.

    qty_TreatedFrames
        Number of frames treated, where applicable.


COLONY ASSESSMENT VARIABLES
---------------------------

Variables ending in _1, _2, and _3 generally correspond to assessments 1, 2,
and 3, respectively.

    InspectionDate
        Date of colony assessment.

    BroodPattern
        Brood pattern assessment.

    Eggs
        Presence/observation of eggs.

    Qstatus
        Queen status.

    Bees
        Colony adult bee population or associated bee count, depending on
        assessment.

    Lieb_Bees
        Frames of adult bees estimated using the Liebefeld method.

    Lieb_WorkerBrood
        Frames of worker brood estimated using the Liebefeld method.

    Lieb_DroneBrood
        Frames of drone brood estimated using the Liebefeld method.

    Lieb_Honey
        Frames of honey estimated using the Liebefeld method.

    Lieb_Pollen
        Frames of pollen estimated using the Liebefeld method.

    Varroa
        Number of Varroa destructor mites detected in the assessment.

    Mite_per_100
        Varroa infestation expressed as mites per 100 adult bees.

    Infestation
        Recorded infestation measurement where available.

    Notes
        Field notes associated with the assessment.

QUEEN AND SWARMING OBSERVATIONS
------------------------------

Additional variables document queen status and observations made during April
2025, including:

    - Queen status observations recorded at multiple dates.
    - Observed Longterm Queen issues and signs of swarming
    - Observed Queen issues and signs of swarming

These variables were used to document queen-related events and evidence of
swarming that could affect colony inclusion in analyses.


EXCLUSION VARIABLES
-------------------

The dataset contains assessment-specific and final exclusion variables,
including:

    exclude
    exclude_3
    AU_Excluded
    AU_reason_As1
    AU_reason_As2
    AU_reason_As3
    AU_reason_fin

These variables identify colonies excluded from particular assessments or
analyses and provide the recorded reason for exclusion.

Colonies could be excluded based on predetermined criteria including high
Varroa infestation, low colony strength, queen loss, or evidence of swarming.
Colonies excluded at a particular assessment were not necessarily excluded
from all previous assessments.


DERIVED VARROA VARIABLES
------------------------

The dataset includes variables describing change in Varroa infestation between
assessment points:

    Delta Varroa Visit 1 and Visit 2
    Delta Varroa Visit 1 and Visit 3
    %Delta Varroa Visit 1 and Visit 2
    %Delta Varroa Visit 1 and Visit 3

These variables represent changes in Varroa infestation between assessment
points and were calculated from the corresponding infestation measurements.


MISSING DATA AND NOTATION
=========================

Missing observations are represented as blank/NA values in the dataset. Some
field observations contain ? or other text indicating that a measurement was
unavailable or not recorded.

Variable names and field terminology reflect the original data collection and
analysis workflow.


DATA PROCESSING AND ANALYSIS
============================

The dataset represents the colony assessment data associated with the study.
Statistical analyses and additional filtering or exclusion procedures are
described in the associated manuscript.

Assessment 1 was the pre-treatment assessment, while assessments 2 and 3 were
used to evaluate changes following treatment.

The dataset includes both original assessment variables and derived variables
used during data analysis. Not all variables in the dataset were used in every
statistical analysis.


STUDY CONTEXT
=============

The study evaluated the use of automated heat treatment or extended release
oxalic acid treatment for management of Varroa destructor in honey bee colonies
and compared outcomes with untreated colonies. Colony-level outcomes included Varroa
infestation, colony strength, brood, honey, pollen, queen status, and colony
loss or exclusion.


ASSOCIATED PUBLICATION
======================

This dataset is associated with the manuscript:

Evaluating the Beewise Heat Chamber™ and VarroxSan® for Spring control 
of Varroa destructor in honey bee colonies

The dataset DOI should be cited when referencing these data.


CITATION
========

Please cite the associated dataset and manuscript when using these data.
