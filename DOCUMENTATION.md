# NFC Manager Application Documentation

## 1. Overview
The **NFC Manager** is a modern Android application built to seamlessly read, write, and format NFC (Near Field Communication) tags. It focuses on standardized NDEF (NFC Data Exchange Format) operations. The application is enhanced with an embedded AI Assistant powered by the Gemini API, which can guide users and execute mode-switching commands via natural language processing.

## 2. Architecture & Tech Stack
The application strictly follows modern Android development best practices:
*   **UI Toolkit:** Jetpack Compose (Material Design 3)
*   **Architecture:** MVVM (Model-View-ViewModel) + Unidirectional Data Flow (UDF)
*   **Language:** Kotlin
*   **Concurrency:** Kotlin Coroutines and StateFlow
*   **Networking (AI Chat):** Retrofit + Moshi (JSON serialization)
*   **Hardware Interface:** Android `NfcAdapter` and `android.nfc.tech.*` libraries.

## 3. Core Requirements & Features

### 3.1. NFC Hardware Interfacing
*   **Foreground Dispatch:** Intercepts NFC intents (`ACTION_NDEF_DISCOVERED`, `ACTION_TAG_DISCOVERED`, `ACTION_TECH_DISCOVERED`) specifically when the app is in the foreground.
*   **NFC Status Monitoring:** Dynamically detects and informs the user if the device's NFC hardware is enabled or disabled using a visual status card.

### 3.2. Operation Modes
The system is divided into four distinct operational modes represented by `NfcOperationMode`:
1.  **READ:** Parses NDEF messages and displays the string payload. Falls back to displaying raw tag ID bytes if the tag is empty or unformatted.
2.  **WRITE:** A standard mode prepared to write NDEF payloads to writable tags.
3.  **RESET:** A standard mode prepared to format tags back to an empty NDEF state.
4.  **WRITE_PROFILE:** A specialized feature to encode user contact data into the industry-standard `vCard` format and write it to the tag.

### 3.3. Digital Profile (vCard) Writer
*   Collects user input (Name, Phone, Email, Website, Instagram, Facebook, Custom Notes).
*   Constructs a `text/x-vcard` MIME type payload.
*   Automatically connects to the NFC tag (`Ndef` or `NdefFormatable`).
*   Verifies tag capacity (`ndef.maxSize`) and writability status (`ndef.isWritable`).
*   Formats and pushes the payload, enabling cross-platform contact sharing via a simple tap.

### 3.4. Export & Sharing (System Intents)
*   **JSON Export:** Tag data read from the device can be dynamically formatted into a JSON string and shared via the Android `ACTION_SEND` intent.
*   **vCard Export:** The digital profile can be shared directly as plain-text vCard data over system intents (email, messaging, etc.) without requiring an NFC tag.

### 3.5. Gemini AI Assistant
*   **Interactive Chat:** A floating action button triggers a Modal Bottom Sheet housing a conversational interface.
*   **Command Parsing:** The Gemini model is prompted via a `systemInstruction` to evaluate user input. If the user requests to change modes (e.g., "Switch to read mode"), the model outputs a secure, internal tag (`[COMMAND:SWITCH_MODE:READ]`).
*   **Action Execution:** The `NfcViewModel` parses incoming AI responses. If a command tag is detected, it strips the tag from the UI response and automatically triggers the requested state change within the application.
*   **API Security:** The Gemini API key is securely injected at build time using the Secrets Gradle Plugin (`BuildConfig.GEMINI_API_KEY`) and is never hardcoded.

## 4. Technical Implementation Details

### 4.1. MainActivity.kt (View & Controller)
*   **Intent Handling:** Implements `onNewIntent` to capture NFC tag discoveries while the Activity is in `SingleTop` launch mode.
*   **PendingIntent Management:** Registers and unregisters `NfcAdapter.enableForegroundDispatch` in the `onResume` and `onPause` lifecycles to ensure intents are routed to this app only when active.

### 4.2. NfcViewModel.kt (State Management)
*   Holds the `NfcUiState` data class wrapped in a `MutableStateFlow`.
*   Maintains lists of `LogEntry` (for the activity log) and `ChatMessage` (for the AI assistant).
*   Executes API calls on `Dispatchers.IO` using `viewModelScope.launch` to prevent blocking the Main Thread.

### 4.3. RetrofitClient & GeminiApiService (Networking)
*   Uses `OkHttpClient` with 60-second timeouts to accommodate potentially long AI generation times.
*   Maps standard JSON request/response formats (`GenerateContentRequest`, `GenerateContentResponse`) to Kotlin data classes using `Moshi`.

## 5. Security and Scope Boundaries
This application strictly adheres to standard Android API limitations and software safety guidelines:
*   **Hardware Integrity:** The app relies on `Ndef.isWritable` and standard SDK methods. It **does not** contain logic to bypass permanent read-only locks or exploit proprietary sector keys (e.g., MIFARE Classic sector cracking).
*   **Financial & Access Data:** The app **does not** implement ISO-DEP / APDU cloning commands necessary to replicate EMV (credit cards) or encrypted transit/access tokens.
*   **Safe Execution:** All interactions are handled at the NDEF abstraction layer, ensuring the app remains safe, legal, and compliant with device security policies.

### 3.6. Data Visualization
*   **Vico Charts:** Uses the `Vico` Jetpack Compose charting library to generate a native, real-time bar chart of operation distributions.
*   **Analytics Layer:** Groups data pulled from the Room database and categorizes frequencies across standard Read, Write, Reset, and Profile format modes.
