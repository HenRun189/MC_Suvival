# MC_Survival

Minecraft Survival Plugin für einen privaten Survival-Server mit Freunden.

Das Plugin basiert auf **Paper 26.3** und enthält eigene Features, die über Vanilla-Minecraft hinausgehen.

---

## 📌 Projekt

**Projekt:** MC_Survival  
**Minecraft:** 26.3  
**Server-Software:** Paper  
**Sprache:** Java  
**Build-System:** Gradle  
**Repository:** GitHub

---

## ✨ Features

### 🪽 Spawn-Elytra

Im definierten Bereich der Welt kann ein Spieler durch Springen und anschließendes Drücken der Leertaste eine temporäre Elytra aktivieren.

Die Spawn-Elytra:

- ersetzt die normale Brustplatte nur während des Fluges
- speichert die ursprüngliche Brustplatte
- stellt die ursprüngliche Brustplatte anschließend wieder her
- kann nicht ins Inventar verschoben werden
- kann nicht gedroppt werden
- kann nicht durch andere Spieler oder Inventaraktionen entfernt werden
- wird beim Tod nicht gedroppt
- wird beim Weltwechsel deaktiviert
- wird beim Verlassen des Flugbereichs deaktiviert
- ist unzerstörbar
- verwendet eine eigene PersistentDataContainer-Markierung

### 🚀 Boost-System

Während der Spawn-Elytra können bis zu **2 Boosts** verwendet werden.

Der Boost:

- beschleunigt den Spieler in Blickrichtung
- gibt zusätzlich einen vertikalen Schub
- kann nicht durch dauerhaftes Halten des Klicks mehrfach ausgelöst werden
- wird nach der Verwendung vom verbleibenden Boost-Kontingent abgezogen

---

## 💰 Wirtschaftssystem

> **Status: In Planung**

Das Server-Geld soll zunächst ausschließlich als **Minecraft-Item** existieren.

Es wird nur **eine Währung** geben.

### Geld als Item

Das Geld wird ein eigenes Custom-Item und nicht einfach ein Diamant oder ein anderes normales Minecraft-Item.

Das Item wird intern eindeutig über die `PersistentDataContainer` erkannt.

Dadurch kann das Plugin unterscheiden zwischen:

```text
Normales Minecraft-Item
        ≠
MC_Survival Geld
