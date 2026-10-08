package com.example.faceunlock

/**
 * Cosine-similarity matcher with consecutive-hit debouncing to avoid a single
 * noisy frame triggering an unlock. Both vectors are expected to be
 * L2-normalized, so cosine similarity reduces to a dot product.
 */
class FaceMatcher(
    private val threshold: Float = DEFAULT_THRESHOLD,
    private val requiredHits: Int = DEFAULT_REQUIRED_HITS
) {

    companion object {
        const val DEFAULT_THRESHOLD = 0.6f
        const val DEFAULT_REQUIRED_HITS = 3
    }

    private var hits = 0

    fun reset() {
        hits = 0
    }

    fun match(embedding: FloatArray, reference: FloatArray): Boolean {
        if (embedding.size != reference.size) return false
        var dot = 0f
        for (i in embedding.indices) dot += embedding[i] * reference[i]
        return if (dot >= threshold) {
            hits++
            hits >= requiredHits
        } else {
            hits = 0
            false
        }
    }
}
