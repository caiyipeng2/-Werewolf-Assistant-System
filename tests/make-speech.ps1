Add-Type -AssemblyName System.Speech
$speech = New-Object System.Speech.Synthesis.SpeechSynthesizer
$speech.SelectVoice('Microsoft Huihui Desktop')
$speech.SetOutputToWaveFile((Join-Path $PSScriptRoot 'assets\chinese.wav'), (New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)))
$speech.Speak('我是三号玩家，我这里是预言家。昨晚查验二号是好人，我的警徽流先四后六。请五号玩家说明投票理由。')
$speech.Dispose()
