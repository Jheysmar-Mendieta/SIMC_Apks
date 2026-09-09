# SIMC — Aplicaciones Android (.APK y Código Fuente)

Repositorio oficial con el código fuente en **Android Studio (Kotlin)** y los instaladores compilados **(.APK)** del ecosistema **SIMC (Sistema Inteligente de Monitoreo y Control)**.

---

## Estructura del Repositorio

El proyecto está organizado como un proyecto multi-módulo en Android Studio:

- **simc_agente/**: Aplicación móvil para los alumnos evaluados (envío de telemetría, capturas, bloqueo seguro y proctoring móvil).
- **simc_supervisor/**: Panel móvil para los docentes y supervisores (visualización en tiempo real del aula, recepción de alertas y control de puestos).
- **simc_individual/**: Versión móvil para estudio individual / modo focus.
- **pks/**: Instaladores compilados listos para instalar en dispositivos Android:
  - SIMC_Agente.apk
  - SIMC_Supervisor.apk
  - SIMC_Individual.apk

---

## Cómo abrir el proyecto en Android Studio

1. Abre **Android Studio**.
2. Selecciona **File -> Open...** y elige esta carpeta raíz.
3. Espera a que Gradle sincronice las dependencias del proyecto.
4. Selecciona el módulo que deseas ejecutar o compilar (simc_agente, simc_supervisor o simc_individual) en la barra superior.

---

## Requisitos
- **Android Studio**: Ladybug / Iguana / Giraffe o superior.
- **JDK**: 17 o superior.
- **Android SDK**: Min SDK 24 (Android 7.0) | Target SDK 34 (Android 14).
