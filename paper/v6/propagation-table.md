# 伝播パラメータの全合成表 / Complete composition table

`PropagationAlgebraTest`の列挙結果。行f・列gは`f . g`（gの後にf）を表す。

| f / g | Id | AllStop | DoConsume | StopInvert | NotProp | ConsumeNot | ForceInvert | AllTrue |
|---|---|---|---|---|---|---|---|---|
| Id | Id | AllStop | DoConsume | StopInvert | NotProp | ConsumeNot | ForceInvert | AllTrue |
| AllStop | AllStop | AllStop | AllStop | AllStop | AllStop | AllStop | AllStop | AllStop |
| DoConsume | DoConsume | AllStop | DoConsume | AllStop | ConsumeNot | ConsumeNot | AllTrue | AllTrue |
| StopInvert | StopInvert | AllStop | AllStop | StopInvert | StopInvert | AllStop | StopInvert | AllStop |
| NotProp | NotProp | AllTrue | ConsumeNot | ForceInvert | Id | DoConsume | StopInvert | AllStop |
| ConsumeNot | ConsumeNot | AllTrue | ConsumeNot | AllTrue | DoConsume | DoConsume | AllStop | AllStop |
| ForceInvert | ForceInvert | AllTrue | AllTrue | ForceInvert | ForceInvert | AllTrue | ForceInvert | AllTrue |
| AllTrue | AllTrue | AllTrue | AllTrue | AllTrue | AllTrue | AllTrue | AllTrue | AllTrue |

`ConsumeNot=(C,!b)`、`ForceInvert=(t,true)`、`AllTrue=(C,true)`。`AllStop`と`AllTrue`は左零元。モデルの対象と限界は本文付録Bを参照。
