# Android SMS Compliance Filter — Testing & Debugging Guide

This guide explains how to run, monitor, and debug the test suite for the **SMS Compliance Filter** application. It is written specifically for developers, QA testers, and contributors who may not be familiar with Android, Java, or Kotlin development.

---

## 1. Understanding the Test Suite

The application has two distinct types of tests:

```
app/src/
  ├── test/             <-- Local Unit Tests (Run on your computer)
  └── androidTest/      <-- Instrumented Tests (Run on a device/emulator)
```

| Test Type | Description | Where It Runs | Execution Speed |
| :--- | :--- | :--- | :--- |
| **Unit Tests** (`/test`) | Tests code logic in isolation (e.g., text pattern matching, phone number normalization) without Android system dependencies. | Your host computer (JVM) | **Very Fast** (seconds) |
| **Instrumented Tests** (`/androidTest`) | Tests integration with Android system features (e.g., Room database access, contact querying, Hilt dependency injection). | A physical Android device or virtual Emulator | **Slower** (requires build + install) |

---

## 2. Running Local Unit Tests

Unit tests do not require an Android device. They are the easiest and fastest way to verify that the opt-out detection rules and stop-lists are working correctly.

### Method A: Using Android Studio (Recommended)

1. Open the project in **Android Studio**.
2. Locate the project hierarchy pane on the left side of the window.
3. Navigate to `app` ➔ `src` ➔ `test` ➔ `java` ➔ `com.digiroth.smsfilter`.
4. **Run all unit tests**: Right-click the folder and select **Run 'Tests in...'** (represented by a green double play arrow icon).
5. **Run a single test class**: Right-click a specific file (e.g., `OptOutDetectorTest`) and select **Run 'OptOutDetectorTest'**.
6. **Run a specific test case**: Open a test file. In the left margin (gutter) next to the class declaration or individual `@Test` functions, click the green play button (`▶`) and choose **Run**.

The test results will appear at the bottom of Android Studio in the **Run** tool window.

### Method B: Using the Command Line (Terminal)

You can run unit tests directly from the root of your project directory using the Gradle wrapper (`gradlew`).

* **On macOS / Linux:**
  ```bash
  ./gradlew testDebugUnitTest
  ```
* **On Windows (Command Prompt / PowerShell):**
  ```cmd
  gradlew.bat testDebugUnitTest
  ```

#### Finding the HTML Test Report
After running the command line tests, a visual HTML report is generated. You can open it in any web browser to view failures, passing rates, and diagnostics:
* **Report Path:** `[Project Root]/app/build/reports/tests/testDebugUnitTest/index.html`

---

## 3. Running Instrumented Tests

Instrumented tests require a target device (either a physical Android phone or an emulator) because they interact with the Android OS (such as querying a mock database or contacts provider).

### Step 1: Set Up an Android Device/Emulator

#### Option 1: Using the Android Virtual Device (AVD) Emulator (Inside Android Studio)
1. In Android Studio, click **Tools** ➔ **Device Manager** from the top menu.
2. Click **Create Virtual Device**.
3. Choose a device definition (e.g., **Pixel 8**) and click **Next**.
4. Select a system image matching the target SDK (e.g., **API 35 / Android 15.0**) and click **Next**.
5. Click **Finish**.
6. Launch the virtual device by clicking the green play icon next to it in the Device Manager.

#### Option 2: Connecting a Physical Android Device
1. On your physical Android phone, go to **Settings** ➔ **About Phone**.
2. Tap **Build Number** 7 times until you see a message saying *"You are now a developer!"*.
3. Go back to **Settings** ➔ **System** ➔ **Developer Options**.
4. Enable **USB Debugging**.
5. Connect your phone to your computer via USB.
6. A prompt will appear on your phone asking to trust the computer. Select **Allow**.

> [!NOTE]
> Make sure your device/emulator is selected in the running devices dropdown menu at the top center of Android Studio before proceeding.

### Step 2: Executing Instrumented Tests

#### Method A: Using Android Studio (Recommended)
1. In the project explorer, navigate to `app` ➔ `src` ➔ `androidTest` ➔ `java` ➔ `com.digiroth.smsfilter`.
2. Right-click the folder and select **Run 'Tests in...'** (with the Android icon).
3. The test runner will compile the code, upload two APKs (the main app and a test runner APK) to your device, run them, and stream the results back to the **Run** window.

#### Method B: Using the Command Line
Ensure your device or emulator is connected and running (you can check by running `adb devices` in your terminal).

* **On macOS / Linux:**
  ```bash
  ./gradlew connectedAndroidTest
  ```
* **On Windows:**
  ```cmd
  gradlew.bat connectedAndroidTest
  ```

#### Finding the HTML Test Report
* **Report Path:** `[Project Root]/app/build/reports/androidTests/connected/index.html`

---

## 4. Debugging & Troubleshooting for Beginners

If a test fails or the app is not behaving as expected, use these techniques to inspect and debug the code.

### 1. Breakpoints and Stepping
A breakpoint pauses code execution at a specific line so you can inspect variables and application state.

1. Open the Kotlin file you want to inspect (e.g., `OptOutDetector.kt`).
2. Click in the gray vertical bar (the gutter) to the left of the line number where you want to pause. A **red circle** will appear.
3. Instead of clicking the standard green Play button, click the **Debug** button (looks like a green bug `🪲` next to the play icon).
4. Run your test or trigger the event. When the app hits the breakpoint line, execution pauses, the line turns blue, and the **Debug Window** opens at the bottom.

#### Stepping Navigation Controls
Use the debugging controls toolbar to step through the paused code:
* **Step Over (`F8`)**: Executes the current line of code and stops on the very next line in the same file.
* **Step Into (`F7`)**: Enters the method/function defined on the current line to debug it line-by-line.
* **Step Out (`Shift + F8`)**: Finishes the current method/function and returns to the calling code.
* **Resume Program (`F9`)**: Continues normal execution until it hits another breakpoint or finishes.

---

### 2. Inspecting Variables
While paused on a breakpoint:
* **Variables Pane**: Look at the **Variables** tab in the Debug window. It lists all variables currently in scope and their values (e.g., checking if `replyType` is `"stop"` or `"end"`).
* **Inline values**: Android Studio displays the value of variables in light gray text directly inside the code editor next to where they are defined.
* **Evaluate Expression (`Option + F8` / `Alt + F8`)**: Click the calculator icon in the Debug toolbar. You can type any Kotlin code (e.g., `incomingMessage.lastLine()`) to see what it returns on the fly.

---

### 3. Monitoring System Logs (Logcat)
The Android OS and your application print system logs containing errors, warnings, and debug logs. You can inspect these logs using the **Logcat** tool.

1. Click the **Logcat** tab at the bottom of Android Studio.
2. In the search/filter bar, enter the following filters to isolate only what you need:
   * **Only show logs from your app:**
     `package:mine` or `package:com.digiroth.smsfilter`
   * **Only show errors:**
     `level:error`
   * **Filter by specific worker or receiver tag:**
     `tag:SmsLookupWorker` or `tag:SmsReceiver`
3. If you want to log something custom in the code to debug, add a log statement like this:
   ```kotlin
   import android.util.Log
   
   Log.d("SmsLookupWorker", "Processing incoming message from unknown sender")
   ```

---

## 5. Common Troubleshooting Scenarios

> [!WARNING]
> **Build Error: "Android SDK Build-Tools is missing"**
> * **Fix**: Go to **Tools** ➔ **SDK Manager** ➔ **SDK Tools** tab. Check the box for the matching SDK Build-Tools version, click **Apply**, and wait for it to install.

> [!CAUTION]
> **Test Fails to Start: "Device unauthorized" or "No devices connected"**
> * **Fix**: Unplug your USB cable and plug it back in. Look at your phone's screen and make sure you accept the authorization prompt. Run `adb devices` in your command line to make sure your device shows up as `device` and not `unauthorized`.

> [!TIP]
> **Clean & Rebuild Project**
> If you experience strange compilation errors or Room database schema mismatch exceptions, reset the project cache:
> * Go to **Build** ➔ **Clean Project**, then **Build** ➔ **Rebuild Project**.
