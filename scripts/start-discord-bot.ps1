# Starts Discord bot process from repository root using Python 3.11 venv.
Set-Location "$PSScriptRoot\..\discord-bot"

if (!(Test-Path ".venv311")) {
    py -3.11 -m venv .venv311
}

.\.venv311\Scripts\python.exe -m pip install -r requirements.txt
.\.venv311\Scripts\python.exe -m bot.main
