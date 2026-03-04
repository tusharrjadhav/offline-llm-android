---
name: compose-viewmodel-udf
description: Build or refactor Jetpack Compose features to a scalable ViewModel-first Unidirectional Data Flow structure with UiState, Event, Effect, Route, and stateless Screen patterns. Use when implementing new Compose screens, cleaning mixed UI/business logic, or standardizing testable Android UI architecture.
---

# Compose ViewModel UDF

## Apply This Workflow

1. Create feature files with this structure:

```text
feature/<name>/
- <Name>Route.kt
- <Name>Screen.kt
- <Name>ViewModel.kt
- <Name>UiState.kt
- <Name>Event.kt
- <Name>Effect.kt      // optional
```

2. Keep `Screen` stateless and preview-friendly.
3. Keep business logic in `ViewModel` only.
4. Expose one immutable `StateFlow<UiState>`.
5. Send UI actions to `ViewModel` via a single `onEvent(event)` entry point.
6. Emit one-off actions as effects (`SharedFlow` or `Channel`), not persistent state.

## Decision Rules

- Keep value in composable `remember` only if it is purely visual/ephemeral.
- Hoist to `ViewModel` if it affects business rules, survives configuration/process, or is shared.
- Use `data class UiState` for additive state.
- Use sealed `UiState` only for mutually exclusive states like `Loading/Error/Content`.

## Templates

### 1) UiState

```kotlin
data class HomeUiState(
    val isLoading: Boolean = false,
    val items: List<ItemUi> = emptyList(),
    val errorMessage: String? = null,
    val query: String = "",
    val isRefreshing: Boolean = false
)
```

### 2) Event

```kotlin
sealed interface HomeEvent {
    data object OnRefresh : HomeEvent
    data class OnQueryChanged(val value: String) : HomeEvent
    data class OnItemClick(val id: String) : HomeEvent
    data object OnRetry : HomeEvent
}
```

### 3) Effect (one-off)

```kotlin
sealed interface HomeEffect {
    data class ShowSnackbar(val message: String) : HomeEffect
    data class NavigateToDetails(val id: String) : HomeEffect
}
```

### 4) ViewModel

```kotlin
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val getItems: GetItemsUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState(isLoading = true))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<HomeEffect>()
    val effects: SharedFlow<HomeEffect> = _effects.asSharedFlow()

    init {
        loadItems()
    }

    fun onEvent(event: HomeEvent) {
        when (event) {
            is HomeEvent.OnQueryChanged -> {
                _uiState.update { it.copy(query = event.value) }
            }
            HomeEvent.OnRefresh,
            HomeEvent.OnRetry -> loadItems(force = true)
            is HomeEvent.OnItemClick -> {
                viewModelScope.launch {
                    _effects.emit(HomeEffect.NavigateToDetails(event.id))
                }
            }
        }
    }

    private fun loadItems(force: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, isRefreshing = force) }
            runCatching { getItems(force) }
                .onSuccess { items ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            items = items.map(::toItemUi)
                        )
                    }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = throwable.message ?: "Something went wrong"
                        )
                    }
                    _effects.emit(HomeEffect.ShowSnackbar("Failed to load"))
                }
        }
    }
}
```

### 5) Route (stateful wiring)

```kotlin
@Composable
fun HomeRoute(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToDetails: (String) -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is HomeEffect.NavigateToDetails -> onNavigateToDetails(effect.id)
                is HomeEffect.ShowSnackbar -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    HomeScreen(
        state = uiState,
        onEvent = viewModel::onEvent
    )
}
```

### 6) Screen (stateless UI)

```kotlin
@Composable
fun HomeScreen(
    state: HomeUiState,
    onEvent: (HomeEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        SearchBar(
            value = state.query,
            onValueChange = { onEvent(HomeEvent.OnQueryChanged(it)) }
        )

        when {
            state.isLoading -> LoadingContent()
            state.errorMessage != null -> ErrorContent(
                message = state.errorMessage,
                onRetry = { onEvent(HomeEvent.OnRetry) }
            )
            else -> ItemList(
                items = state.items,
                onItemClick = { onEvent(HomeEvent.OnItemClick(it)) }
            )
        }
    }
}
```

## Quality Checklist

- `Route` owns lifecycle collection and effect handling.
- `Screen` has no ViewModel reference.
- `UiState` is immutable and serializable-friendly.
- No one-off navigation/snackbar fields stored in `UiState`.
- `onEvent` is the only event ingress.
- Public ViewModel API is minimal (`uiState`, `effects`, `onEvent`).

## Testing Pattern

- Unit test `ViewModel` event -> state transitions.
- Unit test effect emission for one-off actions.
- Compose UI test `Screen` as pure function with fake `UiState`.

## Refactor Pattern (Existing Screen)

1. Identify all mutable UI flags and data.
2. Move business-relevant fields into `UiState`.
3. Replace direct callback lambdas with `Event` types.
4. Move side effects to `Effect` stream.
5. Split composable into `Route` + stateless `Screen`.
6. Add tests before/after behavior parity.
