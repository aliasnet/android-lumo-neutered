# Lumo Android App

Lumo is the privacy-first AI assistant created by Proton, the team behind encrypted email, VPN, password manager, and cloud storage trusted by over 100 million people.
Lumo helps you stay productive, curious, and informed — without ever compromising your privacy.

This is the native Android application wrapper for the Lumo web application ([lumo.proton.me](https://lumo.proton.me)) with addition features e.g. voice entry.

## 🏗️ Architecture Overview

The Lumo Android app follows a clean, modular architecture with clear separation of concerns:

```mermaid
graph TB
    subgraph "📱 Lumo Android App"
        MA["MainActivity
        📋 Main Entry Point"]
        
        subgraph "🎛️ Manager Layer"
            BMWrapper["BillingManagerWrapper
            💳 Payment Processing"]
            WVM["WebViewManager
            🌐 WebView Control"]
            PM["PermissionManager
            🔐 Permissions & File Access"]
            UIM["UIManager
            🎨 UI Configuration"]
        end
        
        subgraph "🧠 ViewModels & State"
            MAVM["MainActivityViewModel
            📊 App State Management"]
            SVM["SubscriptionViewModel
            💰 Subscription Logic"]
            VMF["ViewModelFactory
            🏭 ViewModel Creation"]
        end
        
        subgraph "📦 Data Layer"
            SR["SubscriptionRepository
            📄 Interface"]
            SRI["SubscriptionRepositoryImpl
            🔧 Implementation"]
            DP["DependencyProvider
            ⚡ Lightweight DI"]
        end
        
        subgraph "🌐 WebView Integration"
            WVS["WebViewScreen
            📺 WebView UI Component"]
            WAI["WebAppInterface
            🔗 JS ↔ Android Bridge"]
            JSI["JsInjector
            💉 JavaScript Injection"]
        end
        
        subgraph "💳 Billing System"
            BM["BillingManager
            🏪 Google Play Billing"]
            PD["PaymentDialog
            💸 Payment UI"]
        end
        
        subgraph "🎤 Speech Recognition"
            SRM["SpeechRecognitionManager
            🗣️ Voice Input"]
            SIS["SpeechInputSheet
            🎙️ Voice UI"]
        end
        
        subgraph "📱 UI Components"
            SC["SubscriptionComponent
            💎 Premium Features UI"]
            LS["LoadingScreen
            ⏳ Loading States"]
            Theme["Theme System
            🎨 Material Design 3"]
        end
        
        subgraph "🛠️ Utilities"
            Utils["Utils Package
            🔧 Helper Functions"]
            Models["Models
            📋 Data Classes"]
            Config["LumoConfig
            ⚙️ App Configuration"]
        end
        
        subgraph "🏗️ Build Variants"
            Standard["Standard Variant
            🔧 WebView Debugging ON"]
            NoDebug["NoWebViewDebug Variant
            🛡️ GrapheneOS Compatible"]
        end
    end
    
    subgraph "🌍 External Services"
        Web["Lumo Web App
        🌐 lumo.proton.me"]
        GP["Google Play Billing
        💳 Payment Processing"]
        Android["Android System
        📱 Platform Services"]
    end
    
    %% Main connections
    MA --> BMWrapper
    MA --> WVM
    MA --> PM
    MA --> UIM
    MA --> MAVM
    MA --> SRM
    
    %% Manager connections
    BMWrapper --> BM
    WVM --> WVS
    WVM --> WAI
    
    %% ViewModel connections
    VMF --> SVM
    SVM --> SRI
    SRI --> DP
    
    %% WebView connections
    WVS --> WAI
    WAI --> JSI
    WVS --> Web
    
    %% UI connections
    PD --> SVM
    SC --> SVM
    SIS --> SRM
    
    %% External connections
    BM --> GP
    SRM --> Android
    WVS --> Web
    
    %% Build variant connections
    Standard -.-> WVS
    NoDebug -.-> WVS
    
    %% Styling
    classDef manager fill:#e1f5fe
    classDef viewmodel fill:#f3e5f5
    classDef data fill:#e8f5e8
    classDef webview fill:#fff3e0
    classDef billing fill:#fce4ec
    classDef speech fill:#f1f8e9
    classDef ui fill:#e3f2fd
    classDef utils fill:#fafafa
    classDef external fill:#ffebee
    classDef variant fill:#e0f2f1
    
    class BMWrapper,WVM,PM,UIM manager
    class MAVM,SVM,VMF viewmodel
    class SR,SRI,DP data
    class WVS,WAI,JSI webview
    class BM,PD billing
    class SRM,SIS speech
    class SC,LS,Theme ui
    class Utils,Models,Config utils
    class Web,GP,Android external
    class Standard,NoDebug variant
```

## ✨ Key Features

### 🌐 **WebView Integration**
- Displays the Lumo web application within a native Android `WebView` component
- Uses modern `WebView` settings for optimal compatibility and performance
- Includes JavaScript interface (`WebAppInterface`) for bidirectional communication between web app and native Android code
- Handles file uploads initiated from the web interface using `WebChromeClient.onShowFileChooser`

### 🎤 **Speech-to-Text Input**
- Custom voice input experience using Material 3 Modal Bottom Sheet
- Native `android.speech.SpeechRecognizer` for voice capture
- Real-time audio waveform visualization based on microphone input levels
- Direct text injection into the web application's composer using JavaScript
- Comprehensive permission handling for `RECORD_AUDIO`
- On-device recognition detection (API 33+) with status display

### 💳 **In-App Payments (Google Play Billing)**
- Full Google Play Billing integration (`com.android.billingclient:billing-ktx`)
- `BillingManager` class handling connection, queries, and purchases
- `PaymentDialog` composable triggered via JavaScript interface for premium feature purchases
- Subscription management and billing state synchronization


## 🏗️ Build Variants

The app supports multiple build variants to accommodate different use cases:

### 📱 **Environment Variants**
- **`production`**: Production environment (lumo.proton.me)

### 🛡️ **Debugging Variants**
- **`standard`**: Full debugging capabilities including WebView debugging
- **`noWebViewDebug`**: GrapheneOS-compatible variant with WebView debugging completely disabled

### 🔧 **Build Commands**
```bash
# Standard development build (with WebView debugging)
./gradlew assembleProductionStandardDebug

# GrapheneOS-compatible build (no WebView debugging)
./gradlew assembleProductionNoWebViewDebugDebug

# Production release builds
./gradlew assembleProductionStandardRelease
./gradlew assembleProductionNoWebViewDebugRelease
```

## 🚀 Setup & Building

### Prerequisites
- **Android Studio**: Latest stable version recommended
- **Android SDK**: compileSdk 35, minSdk 29
- **Java**: Version 17
- **Kotlin**: 2.0.21

### Building the Project
1. Clone the repository
2. Open the project in Android Studio
3. Ensure you have the required Android SDK versions installed
4. For release builds, configure signing keys in `local.properties`
5. Build using Gradle:
   ```bash
   ./gradlew clean assembleProductionStandardDebug
   ```

## 🔐 Permissions

The app requires the following permissions:
- **`INTERNET`**: Web content access
- **`ACCESS_NETWORK_STATE`**: Network connectivity checks
- **`BILLING`**: Google Play Billing integration
- **`RECORD_AUDIO`**: Speech recognition functionality
- **`READ_MEDIA_IMAGES`** / **`READ_MEDIA_AUDIO`**: File upload support
- **`READ_EXTERNAL_STORAGE`**: Legacy file access (API ≤ 32)


## 📄 License

This project is licensed under the **GNU General Public License v3.0** - see the [LICENSE](LICENSE) file for details.

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request


---

**Built with ❤️ using Kotlin, Jetpack Compose, and Material Design 3** 