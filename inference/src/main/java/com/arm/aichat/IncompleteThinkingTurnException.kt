package com.arm.aichat

import java.io.IOException

/** Safe native rollback of unfinished reasoning; model stays ready. */
class IncompleteThinkingTurnException(message: String) : IOException(message)
