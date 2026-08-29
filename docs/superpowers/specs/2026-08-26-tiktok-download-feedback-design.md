# TikTok download feedback design

## Goal

Make TikTok downloads explain their state instead of appearing to stop silently, while preserving the existing retry and link-refresh behaviour.

## Scope

- Show a localized waiting message while a failed TikTok media URL is being refreshed for retry.
- Preserve the final failure reason in the download row.
- When WorkManager stops a job, persist a resumable paused state rather than leaving a stale downloading state.
- Keep the existing limit of three automatic attempts and the existing Retry button.

## Data flow

1. A media request fails.
2. For a retryable TikTok failure, the worker refreshes the source URL and stores a waiting status with a localized retrying message.
3. WorkManager schedules the next attempt.
4. If all attempts fail, the worker stores the final cause and the row exposes Retry.
5. If the worker is cancelled, it stores Paused so the user can explicitly resume.

## Error handling

- HTTP failures retain their HTTP code for diagnosis but are rendered as a short user-facing message.
- Resolver failures retain their reason and are rendered as a retryable failure.
- Cancellation is not treated as a completed download or an unknown failure.

## Testing

- Add a focused unit-testable policy that maps retryable failures and cancellations to the intended status/message.
- Verify the new test fails before the production change, then passes.
- Run the full unit test suite and debug APK build.
