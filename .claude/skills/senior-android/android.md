# Android Platform Checklist

## Activity

Kiểm tra:

- launchMode;
- task/back stack;
- Activity Result API;
- state restoration;
- finish behavior;
- intent reuse;
- exported.

## Fragment

Kiểm tra:

- view lifecycle;
- childFragmentManager;
- parentFragmentManager;
- navigation race;
- Fragment result;
- binding cleanup.

## Service

Kiểm tra:

- foreground service type;
- start restrictions;
- notification;
- Android version behavior.

## BroadcastReceiver

Kiểm tra:

- exported;
- runtime registration flags;
- lifecycle unregister;
- Android version restrictions.

## WorkManager

Kiểm tra:

- unique work;
- retry;
- backoff;
- constraints;
- idempotency;
- duplicated scheduling.

## Notification

Kiểm tra:

- notification permission;
- PendingIntent mutability;
- channel;
- deep link behavior.

## WebView

Kiểm tra:

- JavaScript interface;
- file access;
- mixed content;
- URL allow-list;
- lifecycle;
- caching;
- untrusted HTML.
