import json
import os
import time
import warnings
from pathlib import Path


def run(private_dir, task, started_callback):
    root = Path(private_dir) / "probe"
    root.mkdir(exist_ok=True)
    os.environ["MPLCONFIGDIR"] = str(root / "matplotlib")
    os.environ["TMPDIR"] = str(root)
    if task == "sleep":
        time.sleep(300)
        return "slept"
    if task == "infinite":
        started_callback.run()
        while True:
            pass
    if task != "packages":
        raise ValueError("Unknown task: " + task)

    import numpy as np
    import pandas as pd
    import openpyxl
    import matplotlib
    matplotlib.use("Agg")
    from matplotlib import pyplot as plt
    from matplotlib.font_manager import FontProperties
    from matplotlib.ft2font import FT2Font

    matrix = np.arange(9).reshape(3, 3)
    assert int((matrix @ matrix)[2, 2]) == 111
    frame = pd.DataFrame({"group": ["a", "a", "b"], "value": [2, 3, 7]})
    assert frame.groupby("group")["value"].sum().to_dict() == {"a": 5, "b": 7}
    book_path = root / "probe.xlsx"
    book = openpyxl.Workbook()
    book.active.append(["项目", "数值"])
    book.active.append(["合计", int(frame["value"].sum())])
    book.save(book_path)
    loaded = openpyxl.load_workbook(book_path, data_only=True)
    assert loaded.active["A2"].value == "合计"
    assert loaded.active["B2"].value == 12
    loaded.close()
    book.close()

    text = "中文热力图行列"
    chosen = None
    for directory, _, names in os.walk("/system/fonts"):
        for name in sorted(names):
            if name.lower().endswith((".ttf", ".otf", ".ttc")):
                candidate = os.path.join(directory, name)
                try:
                    glyphs = FT2Font(candidate).get_charmap()
                    if all(ord(char) in glyphs for char in text):
                        chosen = candidate
                        break
                except (OSError, RuntimeError):
                    continue
        if chosen:
            break
    assert chosen is not None, "No system font covers all Chinese heatmap labels"
    font = FontProperties(fname=chosen)
    image_path = root / "heatmap.png"
    with warnings.catch_warnings():
        warnings.filterwarnings("error", message="Glyph .* missing from font.*")
        fig, axis = plt.subplots()
        try:
            image = axis.imshow(matrix)
            axis.set_title("中文热力图", fontproperties=font)
            axis.set_xlabel("列", fontproperties=font)
            axis.set_ylabel("行", fontproperties=font)
            fig.colorbar(image, ax=axis)
            fig.savefig(image_path)
        finally:
            plt.close(fig)
    assert image_path.stat().st_size > 1000
    assert image_path.read_bytes()[:8] == b"\x89PNG\r\n\x1a\n"
    return json.dumps({"numpy": np.__version__, "pandas": pd.__version__,
                       "matplotlib": matplotlib.__version__, "openpyxl": openpyxl.__version__,
                       "font": chosen, "xlsx": str(book_path), "png": str(image_path),
                       "matrix_result": 111, "sum": 12}, ensure_ascii=False)
