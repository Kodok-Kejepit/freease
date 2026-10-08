freease

Defensive Reconnaissance and Attack Surface Audit Suite

"freease" is a Python-based security reconnaissance toolkit for authorized defensive assessments, security research, and attack-surface auditing.

Version: 2.2.0
Author: Kodok-Kejepit
Language: Python 3.9+

---

Overview

Freease combines multiple reconnaissance, security-analysis, metadata, URL-analysis, and terminal media utilities into a single command-line application.

The project is designed around a simple principle:

«Collect useful information from legitimate sources, keep the analysis transparent, and never access systems without authorization.»

Freease primarily works with publicly available information, passive network checks, third-party intelligence services, and locally supplied files or URLs.

---

Disclaimer

Freease is intended for:

- academic research
- defensive security assessment
- authorized reconnaissance
- attack-surface auditing
- security testing of systems and assets you own or are explicitly authorized to assess

Only use Freease against domains, accounts, IP addresses, URLs, files, or other assets for which you have appropriate authorization.

The author is not responsible for misuse of this software.

---

Features

Module| Function
NetworkRecon| DNS, subdomain discovery, web technology, SSL/TLS
PortScan| Asynchronous TCP port scanning and banner grabbing
WAFDetector| Web Application Firewall fingerprinting
WhoisChecker| Domain registration, registrar, RDAP, domain age
EmailSecurityChecker| SPF, DKIM, DMARC, MTA-STS, BIMI validation
UsernameChecker| Username lookup across 20 platforms
BreachChecker| Email breach checking through Have I Been Pwned
IPReputationChecker| AbuseIPDB, AlienVault OTX, and ip-api
ExifToolExtractor| EXIF, GPS, IPTC, XMP, and file metadata
YouTubeDownloader| Video and audio download utilities
URLScanner| Passive phishing and malicious-URL analysis
MediaPlayer| Audio/video playback directly from the terminal
ReportGenerator| JSON and HTML report generation

---

Requirements

Core

- Python 3.9 or newer
- Internet connection for network-based modules
- Python dependencies listed in "requirements.txt"

Install the Python dependencies:

pip install -r requirements.txt

Run the application:

python freease.py --help

Optional system tools

Some modules can use external programs when available.

Tool| Used by| Example installation
"ffmpeg"| YouTube conversion, media processing, "ffprobe"| "sudo apt install ffmpeg"
"mpv"| Terminal media player| "sudo apt install mpv" / "pkg install mpv"
"mpg123"| Lightweight audio fallback| "sudo apt install mpg123"
"chafa" / "timg"| ANSI video rendering| "sudo apt install chafa"
"node" / "deno"| Additional YouTube format support| "sudo apt install nodejs"
"exiftool"| Metadata extraction| "sudo apt install libimage-exiftool-perl"

The URL Safety Scanner does not require additional system tools.

---

Quick Start

Network reconnaissance

python freease.py -d example.com

Specify an output directory:

python freease.py -d example.com -o ./reports

Skip the port scanner:

python freease.py -d example.com --skip-portscan

Username lookup

python freease.py -u johndoe

Freease checks the username across 20 supported platforms.

A result reported as "UNKNOWN" means the platform could not be reliably verified, usually because authentication, anti-bot protection, or other restrictions prevent automated confirmation.

Email breach check

python freease.py \
  -e admin@example.com,info@example.com \
  --hibp-key YOUR_HIBP_KEY \
  -o ./reports

Have I Been Pwned API:

https://haveibeenpwned.com/API/Key

IP reputation

python freease.py \
  -i 1.2.3.4,8.8.8.8 \
  --abuseipdb-key YOUR_ABUSEIPDB_KEY

Metadata extraction

Local file:

python freease.py -x /path/to/photo.jpg

Remote file:

python freease.py -x https://example.com/image.jpg

Full reconnaissance

python freease.py \
  -d example.com \
  -u johndoe \
  -e admin@example.com \
  --hibp-key YOUR_HIBP_KEY \
  -i 1.2.3.4 \
  -o ./reports

Terminal-only output

python freease.py -d example.com --no-export

---

URL Safety Scanner

The URL Safety Scanner performs passive analysis of potentially suspicious links.

Basic scan:

python freease.py -s "https://example.com/login"

Use URLhaus threat intelligence:

python freease.py -s "https://example.com" --urlhaus

Perform lexical analysis without network requests:

python freease.py -s "https://example.com" --offline

Batch scanning:

python freease.py --scan-list urls.txt -o ./reports

Optional Google Safe Browsing integration:

python freease.py \
  -s "https://example.com" \
  --safebrowsing-key YOUR_KEY

Analysis layers

Lexical analysis

- raw IP hosts
- "user@host" tricks
- Punycode and IDN homographs
- lookalike characters
- typosquatting
- suspicious TLDs
- URL shorteners
- excessive subdomain depth
- phishing-related keywords
- high-entropy domain labels
- "data:" and "javascript:" URLs
- suspicious file extensions

Network analysis

- DNS resolution
- redirect tracing through "HEAD"
- cross-domain redirect detection
- TLS certificate information
- hostname and certificate matching
- certificate age indicators
- domain age through RDAP

Threat intelligence

- abuse.ch URLhaus
- Google Safe Browsing

The scanner is designed as a passive safety-analysis tool. It does not render or execute the target page.

Risk scores range from "0" to "100".

Possible verdicts:

SAFE
SUSPICIOUS
DANGEROUS

Exit codes:

0  SAFE
1  SUSPICIOUS
2  DANGEROUS

This makes the scanner suitable for shell scripting and automation.

---

YouTube and YouTube Music

Interactive mode:

python freease.py -y "https://youtu.be/VIDEO_ID"

Download a specific video quality:

python freease.py \
  -y "https://youtu.be/VIDEO_ID" \
  --yt-mode video \
  --yt-quality 4k

Audio:

python freease.py \
  -y "https://music.youtube.com/watch?v=ID" \
  --yt-mode musik

Custom audio format and bitrate:

python freease.py \
  -y "https://music.youtube.com/watch?v=ID" \
  --yt-mode musik \
  --yt-audio-format m4a \
  --yt-bitrate 320

Playlist or album:

python freease.py \
  -y "https://youtube.com/playlist?list=ID" \
  --yt-mode musik \
  --yt-playlist

Supported quality options

4k      2160p
2k      1440p
hd      1080p
720     720p
480     480p
360     360p
240     240p
144     144p
best    Best available

Audio formats

mp3
m4a
opus
flac
wav

The downloader can automatically fall back to a lower available resolution when the requested quality is unavailable.

Music downloads can also include title, artist, and cover metadata.

Use download functionality only for content you own or are authorized to download.

---

Media Player

Freease includes a terminal media player with automatic backend detection.

Play a single audio file:

python freease.py -p song.mp3

Play an entire directory:

python freease.py -p ./Music

Shuffle and loop:

python freease.py -p ./Music --shuffle --loop

Play video:

python freease.py -p video.mkv

Audio-only playback:

python freease.py -p video.mkv --audio-only

Stream directly from a URL:

python freease.py \
  -p "https://youtu.be/VIDEO_ID" \
  --pl-backend mpv

Show the playback queue without starting playback:

python freease.py -p ./Music --list

Playback options

Option| Function
"--audio-only"| Play audio even when the source contains video
"--loop"| Repeat the current file or playlist
"--shuffle"| Randomize playlist order
"--volume 0-200"| Set initial volume
"--start POS"| Start from a specific position
"--speed X"| Set playback speed
"--subtitle FILE"| Load an external subtitle
"--pl-backend"| Force a specific playback engine
"--no-recursive"| Do not scan subdirectories
"--list"| Show the queue without playing

Supported playback backends include:

mpv
ffplay
mplayer
mpg123
cvlc
vlc
timg

On Linux or Termux environments without a graphical display, Freease can render video directly inside the terminal when a compatible backend is available.

---

Command Reference

Option| Description
"-d DOMAIN"| Network reconnaissance target
"-u USERNAME"| Username lookup
"-e EMAIL[,EMAIL2]"| Email breach checking
"-i IP[,IP/DOMAIN]"| IP/domain reputation
"-x FILE/URL"| Metadata extraction
"-y URL"| YouTube / YouTube Music downloader
"-s URL"| Passive URL safety scanner
"--scan-list FILE"| Batch URL scanning
"-p SRC..."| Terminal media player
"--hibp-key KEY"| Have I Been Pwned API key
"--abuseipdb-key KEY"| AbuseIPDB API key
"--skip-portscan"| Disable port scanning
"-o DIR"| Report output directory
"--no-export"| Disable JSON/HTML report export

Run the full command reference:

python freease.py --help

---

Output

By default, reports are written to the configured output directory.

Example:

freease_output/
├── freease_report_YYYYMMDD_HHMMSS.json
└── freease_report_YYYYMMDD_HHMMSS.html

JSON

Structured output for:

- automation
- scripting
- data processing
- archival
- further analysis

HTML

Interactive report containing:

- security score
- categorized findings
- expandable sections
- readable summaries
- visual indicators

---

Architecture

freease.py
├── NetworkRecon
├── PortScanner
├── WAFDetector
├── WhoisChecker
├── EmailSecurityChecker
├── UsernameChecker
├── BreachChecker
├── IPReputationChecker
├── ExifToolExtractor
├── ReportGenerator
└── FreeaseEngine

freease_youtube.py
└── YouTubeDownloader

freease_scan.py
└── URLScanner

freease_player.py
└── MediaPlayer

The main reconnaissance workflow uses asynchronous execution with "asyncio" and "aiohttp" to run independent checks concurrently.

The architecture is intentionally modular so individual components can also be used independently where supported.

---

Data Sources

Freease can use publicly accessible or explicitly authorized services and data sources such as:

Source| Purpose
DNS resolvers| DNS records
crt.sh| Certificate Transparency data
Cert Spotter| Certificate Transparency fallback
HTTP responses| Server and technology detection
RDAP| Domain registration and age
Have I Been Pwned| Email breach information
AbuseIPDB| IP reputation
AlienVault OTX| Threat intelligence
ip-api| IP geolocation and network data
URLhaus| Malicious URL intelligence
Google Safe Browsing| Optional URL reputation

Availability and accuracy depend on the external service being accessible and willing to respond to automated requests.

---

Security and Legal Boundaries

Freease does not intentionally provide or perform:

- brute-force authentication
- credential attacks
- exploitation of vulnerabilities
- unauthorized system access
- access to private data
- destructive actions

The project is intended for reconnaissance, analysis, auditing, and defensive research within authorized scope.

---

License

See ""LICENSE"" (LICENSE) for the full license text.

---

Author

*Kodok-Kejepit*

Freease is an independent security-tool project focused on practical defensive reconnaissance and terminal-first workflows.

---

Version

Current release:

freease v2.2.0

Project repository:

https://github.com/Kodok-Kejepit/freease

