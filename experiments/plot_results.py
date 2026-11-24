import csv
import statistics
import sys
from pathlib import Path
from html import escape

root = Path(sys.argv[1])
groups = {}
with (root / 'runs.csv').open() as source:
    for row in csv.DictReader(source):
        key = tuple(int(row[k]) for k in ('rows', 'batch', 'workers'))
        groups.setdefault(key, []).append(float(row['milliseconds']))
medians = {key: statistics.median(values) for key, values in groups.items()}
with (root / 'summary.csv').open('w', newline='') as target:
    writer = csv.writer(target)
    writer.writerow(['rows', 'batch', 'workers', 'median_ms', 'speedup', 'efficiency'])
    for (rows, batch, workers), elapsed in sorted(medians.items()):
        speedup = medians[rows, batch, 0] / elapsed
        writer.writerow([rows, batch, workers, f'{elapsed:.3f}', f'{speedup:.4f}',
                         f'{speedup / workers:.4f}' if workers else ''])

colors = ['#2563eb', '#dc2626', '#15803d']
sizes = sorted({key[0] for key in medians})
batches = sorted({key[1] for key in medians})
svg = ['<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="1120" viewBox="0 0 1000 1120">',
       '<rect width="1000" height="1120" fill="white"/>',
       '<g font-family="sans-serif" font-size="15" fill="#111827">']
for panel, metric in enumerate(['median_ms', 'speedup', 'efficiency']):
    top = 60 + panel * 350
    svg.append(f'<text x="60" y="{top - 20}">{escape(metric)} — {sizes[-1]} rows; x = workers</text>')
    series = []
    for batch in batches:
        points = []
        for workers in [1, 2, 4, 8, 16]:
            elapsed = medians[sizes[-1], batch, workers]
            speedup = medians[sizes[-1], batch, 0] / elapsed
            value = elapsed if metric == 'median_ms' else speedup if metric == 'speedup' else speedup / workers
            points.append((workers, value))
        series.append(points)
    maximum = max(value for points in series for _, value in points) * 1.1
    for tick in range(5):
        y = top + 240 - tick * 60
        svg.append(f'<path d="M80 {y}H800" stroke="#e5e7eb"/><text x="5" y="{y + 5}">{maximum * tick / 4:.2f}</text>')
    for workers in [1, 2, 4, 8, 16]:
        x = 80 + (workers - 1) * 48
        svg.append(f'<text x="{x}" y="{top + 265}">{workers}</text>')
    for index, points in enumerate(series):
        coords = ' '.join(f'{80 + (workers - 1) * 48},{top + 240 - value / maximum * 240}' for workers, value in points)
        svg.append(f'<polyline points="{coords}" fill="none" stroke="{colors[index]}" stroke-width="3"/>')
        svg.append(f'<text x="815" y="{top + index * 25}" fill="{colors[index]}">batch {batches[index]}</text>')
svg.extend(['</g>', '</svg>'])
(root / 'charts.svg').write_text('\n'.join(svg), encoding='utf-8')

report = ['# Измерения параллельного анализа', '',
          'Фактические время и окружение запуска: [environment.txt](results/environment.txt).',
          'Все повторы: [runs.csv](results/runs.csv), медианы: [summary.csv](results/summary.csv).',
          'Время в миллисекундах; S = Tseq / Tparallel, E = S / workers.', '',
          '| Строк | Батч | Потоки | Последовательно, мс | Параллельно, мс | S | E |',
          '|---:|---:|---:|---:|---:|---:|---:|']
for (rows, batch, workers), elapsed in sorted(medians.items()):
    if not workers:
        continue
    baseline = medians[rows, batch, 0]
    speedup = baseline / elapsed
    report.append(f'| {rows} | {batch} | {workers} | {baseline:.3f} | {elapsed:.3f} | {speedup:.3f} | {speedup / workers:.3f} |')
report.extend(['', '![Время, ускорение и эффективность на полном входе](results/charts.svg)', '',
               '## Наблюдения', ''])
for rows in sizes:
    best = min((key for key in medians if key[0] == rows and key[2]), key=medians.get)
    _, batch, workers = best
    speedup = medians[rows, batch, 0] / medians[best]
    report.append(f'Для {rows} строк лучшее измеренное время: {medians[best]:.3f} мс, '
                  f'батч {batch}, потоков {workers}, ускорение {speedup:.3f}.')
    report.append('')
for batch in batches:
    values = [(workers, medians[sizes[-1], batch, workers]) for workers in [1, 2, 4, 8, 16]]
    best_workers, best_time = min(values, key=lambda item: item[1])
    report.append(f'На полном входе с батчем {batch} минимум при {best_workers} потоках '
                  f'({best_time:.3f} мс); при 16 потоках {values[-1][1]:.3f} мс. '
                  'Это граница полезного масштабирования среди проверенных конфигураций, а не универсальный предел.')
    report.append('')
report.extend([
    '## Ограничения и узкие места', '',
    'Читающий поток выполняет весь парсинг до отправки батча. Его работа остаётся последовательной. '
    'Пул распараллеливает только агрегацию; объединение карт выполняется последовательно. '
    'Каждый батч создаёт отдельные карты, включая карту IP. Малые батчи увеличивают число задач и объединений; '
    'крупные батчи уменьшают их число, но на небольшом входе оставляют часть пула без работы. '
    'Лимит задач сдерживает память батчей, однако итоговая карта IP растёт с числом уникальных адресов.', '',
    'Эти свойства кода объясняют возможное ограничение ускорения. Доля CPU, GC, диска и ожиданий '
    'отдельно не измерялась, поэтому по таблице нельзя точно приписать замедление одному из них. '
    'Использованы два прогрева и три повтора; медиана уменьшает влияние единичных выбросов, '
    'но не заменяет доверительные интервалы. Фиксированный порядок и прогретый файловый кеш '
    'ограничивают переносимость результатов. Замеры сделаны в указанную в environment.txt дату; '
    'даты коммитов заданы отдельно по запросу автора.', '',
    '## Дополнительный вариант', '',
    'Общую конкурентную агрегацию рассмотрели как альтернативу: она убирает merge локальных карт, '
    'но требует потокобезопасных счётчиков и обновления минутных окон. Существующий LogEngine '
    'умеет вызывать общие обработчики, однако текущая AnalysisStatistics для этого не предназначена. '
    'Отдельный вариант не реализован и не измерен. Следующий полезный эксперимент — перенос парсинга '
    'в рабочие потоки с ограниченной очередью сырых строк; сейчас последовательный парсинг ограничивает '
    'оба варианта агрегации.'
])
(root.parent / 'RESULTS.md').write_text('\n'.join(report) + '\n', encoding='utf-8')
print(f'Wrote {len(medians)} configurations, charts and RESULTS.md')
