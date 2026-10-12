# Risk Policy

Risk levels:

- LOW: normal chat, mild stress, ordinary anxiety, no self-harm signals.
- MEDIUM: persistent anxiety, insomnia, low mood, pressure, loneliness, emotional distress without immediate self-harm intent.
- HIGH: explicit self-harm, suicide, severe hopelessness, plans to hurt oneself or others, or language suggesting immediate danger.

When risk is HIGH, the system atomically persists the report and durable notification intent before optional retrieval. Notification delivery and the independent Excel ledger write then proceed asynchronously. A retrieval or Excel failure must not erase or delay the durable safety fact. The response should be empathetic and direct, and should encourage immediate human support.
