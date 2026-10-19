# Modello YOLO incluso

Provenienza: `MODELLI/best_320.onnx` dalla versione Chrome v0.11, convertito dal precedente `best.pt`.

| ID | Classe |
|---:|---|
| 0 | ad |
| 1 | airsweeper |
| 2 | bombtower |
| 3 | canon |
| 4 | clancastle |
| 5 | eagle |
| 6 | inferno |
| 7 | kingpad |
| 8 | mortar |
| 9 | queenpad |
| 10 | rcpad |
| 11 | scattershot |
| 12 | th13 |
| 13 | wardenpad |
| 14 | wizztower |
| 15 | xbow |

Output: `[1,6300,21]`, coordinate `cx,cy,w,h`, objectness e 16 score di classe.
Inferenza Android: input RGB normalizzato NCHW 320×320 con letterbox, soglia confidenza 0,32, soppressione NMS per classe (IoU 0,45).

**Non identifica truppe, incantesimi o schieramenti**: cambiamenti della barra sono ipotesi visive da revisionare.
