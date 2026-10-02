package com.example.stepcounter

import kotlinx.coroutines.flow.MutableStateFlow

/** Κοινό σημείο για τα βήματα: η υπηρεσία γράφει, η οθόνη διαβάζει. */
object StepRepo {
    val steps = MutableStateFlow(0)
}
