package app.zoeshorsefarm.domain.testing

import app.zoeshorsefarm.domain.sim.Element
import app.zoeshorsefarm.domain.sim.ElementKind
import app.zoeshorsefarm.domain.sim.Obstacle
import kotlin.math.PI

// Helpers for the sim tests (test support, not production code).

const val DEG = PI / 180
const val DT = 1.0 / 60

private var nextId = 1

/** Creates an element; oxers get [spread] (default 0.7 m), every other kind a spread of 0. */
fun makeElement(
    kind: ElementKind,
    height: Double,
    id: String? = null,
    spread: Double = 0.7,
    x: Double = 0.0,
    z: Double = 0.0,
    rot: Double = 0.0,
): Element =
    Element(
        id = id ?: "e${nextId++}",
        kind = kind,
        height = height,
        spread = if (kind == ElementKind.OXER) spread else 0.0,
        x = x,
        z = z,
        rot = rot,
    )

/** One undirected obstacle without a number per element. */
fun obstaclesOf(vararg elements: Element): List<Obstacle> = elements.map { Obstacle(null, listOf(it), false) }
