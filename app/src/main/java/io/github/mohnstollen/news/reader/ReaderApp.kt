package io.github.mohnstollen.news.reader

import io.github.mohnstollen.news.AppFeatures
import io.github.mohnstollen.news.NewsApp

/** News, the RSS reader. */
class ReaderApp : NewsApp() {
    override val features = AppFeatures.NEWS
}
