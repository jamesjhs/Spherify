# Spherify Privacy and Data Handling Policy (Draft)

Status: Draft for review and future publication on jahosi.co.uk
Version: 0.1-draft
Last updated: 2026-07-22
Applies to app version: 0.4.33 (prototype)

## 1. Who we are

Spherify is developed by Jahosi (the data controller for this app).

Controller (organisation): Jahosi  
Contact email (privacy): privacy@jahosi.co.uk  
Website (planned): https://jahosi.co.uk

If the contact details change, this policy will be updated before production release.

## 2. Scope

This policy explains how Spherify handles personal data when you use the Android app.

This draft is written to align with:

- Google Play User Data and Data Safety requirements
- UK GDPR and Data Protection Act 2018 principles
- UK ICO transparency expectations

## 3. Product state and current behavior

As of version 0.4.33, Spherify is a local-first prototype:

- Captures and processes images on-device
- Saves app content in app-controlled local storage
- Allows local export/share by user action
- Does not provide direct Google Maps publishing in-app yet
- Does not provide Google Photos API upload in-app yet

## 4. Data we process

### 4.1 Data you create in the app

- Captured draft image frames (camera images)
- Saved output images/variants and thumbnails
- Session metadata associated with captured frames, including:
  - session id
  - timestamp
  - approximate heading/pitch/roll
  - capture target references
  - optional location summary if location permission is granted
  - exposure metadata references from camera/ARCore when available

### 4.2 Device and permission-related data

- Camera permission state
- Optional location permission state
- Sensor availability/readiness status (for capture guidance)

### 4.3 Data we do not currently collect as a service backend

In the current prototype, we do not operate a cloud backend for account profiles, direct uploads, or analytics telemetry.

## 5. Permissions and why they are requested

Spherify requests only permissions needed for active features.

- CAMERA:
  - Purpose: capture PhotoSphere draft frames and related image content.
  - User impact if denied: capture flow will be unavailable; existing local browsing remains available.

- ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION (optional):
  - Purpose: optionally attach location summary metadata for map-ready workflows.
  - User impact if denied: core capture/save/reprojection remains usable; location metadata fields stay empty.

Spherify does not require background location for current functionality.

## 6. Lawful bases (UK GDPR)

Depending on the processing activity, we rely on:

- Performance of a contract (Article 6(1)(b)):
  - to provide core app features you request (capture, save, local management, export/share).

- Legitimate interests (Article 6(1)(f)):
  - to maintain app reliability, integrity of local data handling, and secure operation.

- Consent / user choice where applicable:
  - optional permissions such as location are requested contextually and can be declined.

Special category data is not intentionally processed as a dedicated category. Users should avoid capturing sensitive personal content unless necessary.

## 7. How we use data

We use data to:

- Create and manage image content you produce in-app
- Provide projection and editing features
- Preserve local library records and capture sessions
- Support optional map-ready metadata workflows
- Enable user-initiated export/share actions

We do not sell personal data.

## 8. Data sharing

Current prototype behavior:

- Data remains on-device by default.
- Sharing outside the app occurs only when you explicitly use export/share actions.
- If external apps/services are selected by you (for example, Android share targets), their privacy terms apply after transfer.

Planned future integrations (not currently implemented) may include Google services. If implemented, this policy will be updated before release and Play Data Safety declarations will be revised.

## 9. International transfers

Because the current prototype is local-first, routine controller-managed international transfers are not part of core operation.

If future cloud integrations involve international transfers, we will document transfer mechanisms and safeguards in this policy before launch.

## 10. Data retention

Data retention is primarily user-controlled in current versions:

- Captured images, variants, and metadata remain until deleted by the user or app uninstall.
- Draft frame records can be removed individually or in bulk using in-app controls.
- On uninstall, app-private local data is removed by Android according to platform behavior.

Future cloud features (if introduced) will include explicit retention periods.

## 11. Security measures

We apply reasonable technical and organisational measures appropriate to the current app scope, including:

- app-private local storage for core library data
- permission-gated access to camera/location
- least-privilege intent in feature design
- no intentional hidden background collection path for current prototype features

No method of storage or transmission is 100% secure; security controls are continuously reviewed as the product evolves.

## 12. Your rights (UK data protection)

Subject to legal conditions, you may have rights to:

- be informed
- access
- rectification
- erasure
- restrict processing
- data portability
- object
- rights related to automated decision-making

For local-only app data, many controls are directly available in-app (delete/export/uninstall). For privacy requests, contact privacy@jahosi.co.uk.

## 13. Children

Spherify is not intentionally designed to collect data from children as a targeted audience. If we learn data was handled inappropriately for a child, we will review and remediate.

## 14. Complaints

If you are not satisfied with our response, you can complain to the UK Information Commissioner's Office (ICO):

https://ico.org.uk/make-a-complaint/

## 15. Google Play transparency commitments

Before each Play submission, we will ensure:

- Data Safety responses match shipped behavior
- Permission declarations are justified and minimal
- Store listing claims do not overstate implemented features
- Privacy policy content is kept accurate to the released version

## 16. Future updates

We may update this policy as features change. Material updates will be reflected in:

- this policy version and date
- related Play Console declarations
- relevant user-facing documentation

## 17. Data inventory summary (for governance review)

| Data type | Example fields | Source | Purpose | Storage location | Retention | Shared externally by default |
| --- | --- | --- | --- | --- | --- | --- |
| Captured image content | draft JPEG frames, exported variants | Camera/ARCore, user action | capture and output generation | app local storage and user export targets | until user deletes/uninstalls | No |
| Capture metadata | session id, timestamp, heading/pitch/roll, target refs | app sensors/AR pose processing | session continuity and quality workflows | app local metadata files | until user deletes/uninstalls | No |
| Optional location metadata | latitude/longitude summary | device location APIs (if permission granted) | map-ready metadata preparation | app local metadata files | until user deletes/uninstalls | No |
| Permission state | camera/location granted/denied | Android permission model | control feature access and fallback behavior | runtime/app settings context | dynamic, user-controlled | No |

## 18. Publication note

This is a draft policy file intended for legal and governance review before publication on jahosi.co.uk and before production Play release.
