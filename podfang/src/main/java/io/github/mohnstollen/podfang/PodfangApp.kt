package io.github.mohnstollen.podfang

import io.github.mohnstollen.news.AppFeatures
import io.github.mohnstollen.news.NewsApp

/** Podfang, the podcast app. */
class PodfangApp : NewsApp() {
    override val features = AppFeatures.PODFANG
}
