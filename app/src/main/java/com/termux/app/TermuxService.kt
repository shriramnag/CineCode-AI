package com.shriram.cinecode.app

import com.shriram.cinecode.data.service.TermuxService as CoreTermuxService

/**
 * Direct com.shriram.cinecode.app.TermuxService implementation for shell commands
 * (e.g., termux-wake-lock, termux-wake-unlock) targeting explicit component com.shriram.cinecode.app.TermuxService.
 */
class TermuxService : CoreTermuxService()
