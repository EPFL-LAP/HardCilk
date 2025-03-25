from typing import *

import os
import dataclasses
import re


@dataclasses.dataclass
class DataPoint:
    filePath: str
    numFpga: int
    numPe: int
    exp1_delay: int
    efficiency: float


FNAME_REGEX = r"sweep1_(\d+)_exp1_delay(\d+)"
EFFICIENCY_REGEX = r"Efficiency: ([.\d]+)%"


def loadDataPoint(filePath: str) -> DataPoint:
    fileName = os.path.basename(filePath)
    match = re.match(FNAME_REGEX, fileName)

    sweepIndex = int(match.group(1))
    exp1_delay = int(match.group(2))

    vNumFpga = [1, 2, 4, 8]
    vNumPe = [128, 64, 32, 16]

    efficiency = 0.0

    with open(filePath) as f:
        efficiency = float(re.findall(EFFICIENCY_REGEX, f.read())[0])

    return DataPoint(
        filePath,
        vNumFpga[sweepIndex - 1],
        vNumPe[sweepIndex - 1],
        exp1_delay,
        efficiency
    )


def loadDataPoints() -> List[DataPoint]:
    result: List[DataPoint] = []
    dirPath = "results/sweep1"

    for fileName in os.listdir(dirPath):
        filePath = os.path.join(dirPath, fileName)
        dataPoint = loadDataPoint(filePath)
        result.append(dataPoint)

    return result


def main() -> None:
    dataPoints = loadDataPoints()

    import matplotlib.pyplot as plt
    import matplotlib.ticker as mtick

    fig = plt.figure(figsize=(8, 6))
    ax = plt.axes()
    ax.yaxis.set_major_formatter(mtick.PercentFormatter())

    vDataSeries = [
        ("1 FPGA w/ 128 PEs", lambda x: x.numFpga == 1),
        ("2 FPGAs w/ 64 PEs", lambda x: x.numFpga == 2),
        ("4 FPGAs w/ 32 PEs", lambda x: x.numFpga == 4),
        ("8 FPGAs w/ 16 PEs", lambda x: x.numFpga == 8),
    ]

    for dataSeriesName, dataSeriesFilter in vDataSeries:
        dataPointsFiltered: List[DataPoint] = sorted(filter(dataSeriesFilter, dataPoints), key=lambda x: x.exp1_delay)

        x = list(map(lambda x: x.exp1_delay, dataPointsFiltered))
        y = list(map(lambda x: x.efficiency, dataPointsFiltered))

        ax.plot(x, y, label=dataSeriesName)
    
    ax.grid()
    ax.legend()
    ax.set_title("Efficiency vs. Task Delay for Multiple FPGAs")
    fig.tight_layout()

    fig.savefig("sweep1.pdf")


if __name__ == "__main__":
    main()
