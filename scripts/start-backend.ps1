# Starts backend API + WebSocket server from repository root.
Set-Location "$PSScriptRoot\..\backend"
npm install
npm run start
