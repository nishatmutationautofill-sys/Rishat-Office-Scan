# Rishat Office Scan

Native Android document scanner built with Kotlin, CameraX and OpenCV (not the Google ML Kit Document Scanner UI).

## Scanner features
- Custom CameraX capture screen designed for low-latency capture.
- OpenCV contour-based four-corner detection on captured images.
- Perspective correction when a plausible quadrilateral is found.
- CLAHE-based local contrast enhancement for clearer document text.
- Multi-page capture and PDF export, plus first-page JPEG thumbnail.
- Existing PDF list, open/share/rename/delete workflow.
- GitHub Actions debug APK workflow in `.github/workflows/build.yml`.

## Build with GitHub Actions
1. Upload the contents of this repository to GitHub.
2. Open **Actions** and run **Build APK** (or push to `main`/`master`).
3. Download the `RishatOffice-Scan-debug` artifact from the completed workflow.

## Notes / limitations
- Corner detection is automatic after capture; this version does not yet draw live corner guides in the camera preview or provide manual corner handles.
- OpenCV contour detection can miss low-contrast pages, busy backgrounds, or strongly shadowed documents; in those cases the captured image may be used without perspective correction.
- This project has not been built or tested on a physical device in this environment. Verify the GitHub Actions build and test camera permission, capture, multi-page PDF, and saving on your target Android devices.
