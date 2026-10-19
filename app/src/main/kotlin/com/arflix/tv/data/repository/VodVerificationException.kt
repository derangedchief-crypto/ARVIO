package com.arflix.tv.data.repository

/** A failed/unfinished catalogue request is not a verified absence. */
class VodVerificationException : java.io.IOException(
    "Couldn't finish checking your VOD catalogue. Please retry; scraper sources were not requested."
)
