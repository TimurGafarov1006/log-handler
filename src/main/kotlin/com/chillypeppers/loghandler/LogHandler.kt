package com.chillypeppers.loghandler

interface LogHandler {
    fun handle(entries: List<LogEntry>)
}