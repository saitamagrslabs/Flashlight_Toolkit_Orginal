# FlashNow Testing Guide

This document outlines the comprehensive testing setup for the FlashNow Android application.

## Test Structure

The project has two types of tests:

1. **Unit Tests** (`src/test/`): Fast, JVM-based tests for business logic
2. **Instrumented Tests** (`src/androidTest/`): Android device/emulator tests for UI

## Running Tests

### Unit Tests

Run all unit tests:
```bash
./gradlew test
```

Run tests for a specific variant:
```bash
./gradlew testDebugUnitTest
```

### Instrumented Tests

Run all instrumented tests:
```bash
./gradlew connectedAndroidTest
```

Run tests on a specific device:
```bash
./gradlew connectedDebugAndroidTest
```

## Code Coverage

### Generate Coverage Report

Generate JaCoCo coverage report:
```bash
./gradlew jacocoTestReport
```

The report will be available at:
- HTML: `app/build/reports/jacoco/jacocoTestReport/html/index.html`
- XML: `app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml`

### Verify Coverage

Check if coverage meets minimum threshold (30%):
```bash
./gradlew jacocoTestCoverageVerification
```

## Test Dependencies

### Unit Testing
- **JUnit 4**: Core testing framework
- **MockK**: Kotlin mocking library
- **Turbine**: Flow testing library
- **Coroutines Test**: Testing Kotlin coroutines
- **Arch Core Testing**: Testing Architecture Components
- **Robolectric**: JVM-based Android testing
- **Truth**: Assertion library

### Instrumented Testing
- **AndroidX Test**: Core testing framework
- **Espresso**: UI testing framework
- **Fragment Testing**: Fragment scenario testing
- **MockK Android**: Android mocking support

## Test Classes

### Unit Tests (`src/test/`)

- `MorseCodeManagerTest`: Tests Morse code conversion and flashing
- `TimerManagerTest`: Tests timer functionality and presets
- `HomeViewModelTest`: Tests ViewModel state management
- `PermissionManagerTest`: Tests permission handling

### Instrumented Tests (`src/androidTest/`)

- `HomeFragmentInstrumentedTest`: Tests HomeFragment UI
- `SettingsFragmentInstrumentedTest`: Tests SettingsFragment UI
- `AboutFragmentInstrumentedTest`: Tests AboutFragment UI
- `MorseCodeFragmentInstrumentedTest`: Tests MorseCodeFragment UI

## Writing New Tests

### Unit Test Example

```kotlin
@Test
fun `test description should be clear`() {
    // Given - Setup
    val input = "test"
    
    // When - Action
    val result = classUnderTest.process(input)
    
    // Then - Verification
    assertEquals("expected", result)
}
```

### Instrumented Test Example

```kotlin
@Test
fun testFragmentUI() {
    // Launch fragment
    launchFragmentInContainer<YourFragment>()
    
    // Verify UI elements
    onView(withId(R.id.someView))
        .check(matches(isDisplayed()))
}
```

## Best Practices

1. **Test Naming**: Use descriptive names with backticks for Kotlin tests
2. **Arrange-Act-Assert**: Structure tests with clear sections
3. **Given-When-Then**: Use comments to mark test sections
4. **Isolation**: Each test should be independent
5. **Mocking**: Use MockK for Kotlin-friendly mocking
6. **Coroutines**: Use `StandardTestDispatcher` and `runTest`

## Continuous Integration

Add these commands to your CI pipeline:

```bash
# Run unit tests
./gradlew testDebugUnitTest

# Run instrumented tests (requires connected device)
./gradlew connectedDebugAndroidTest

# Generate coverage report
./gradlew jacocoTestReport

# Verify coverage meets threshold
./gradlew jacocoTestCoverageVerification
```

## Troubleshooting

### Tests failing on CI
- Ensure all dependencies are properly declared
- Check that test resources are included
- Verify MockK Android dependencies for instrumented tests

### Coverage report empty
- Ensure `enableUnitTestCoverage = true` in debug build type
- Run tests before generating report
- Check JaCoCo version compatibility

### Robolectric issues
- Ensure `isIncludeAndroidResources = true` in testOptions
- Check SDK version compatibility in `@Config`
