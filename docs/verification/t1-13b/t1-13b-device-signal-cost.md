# T1-13b device per-signal cost (evidence for research doc section 6)
# source log: D:\Projects\MasterMechanic\app\build\replay-work\out\t1-13b-device-log.txt
# generated:  2026-09-13 11:40:44

| 信号 | 本文桌面平均 (ms) | 真机平均 (ms) | 实测倍差 | 真机P95 (ms) | 窗口数 |
| --- | --- | --- | --- | --- | --- |
| `server_select` | 10.029 | 151.28 | x15.1 | 277.90 | 6 |
| `launch_start` | 8.941 | 120.13 | x13.4 | 255.00 | 6 |
| `hall` | 8.451 | 98.14 | x11.6 | 251.90 | 5 |
| `popup_close` | 6.673 | 68.78 | x10.3 | 78.50 | 4 |
| `friend_list` | 1.751 | 21.66 | x12.4 | 71.20 | 5 |
| `friend_farm` | 1.208 | 13.58 | x11.2 | 14.50 | 4 |
| `farm` | 0.588 | 7.40 | x12.6 | 24.90 | 5 |

# mean ratio over 7 measured signal(s) = x12.4
