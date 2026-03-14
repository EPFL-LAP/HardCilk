import json
import os

list_of_jsons_to_edit = ["sweep3_2.json", "sweep3_3.json", "sweep3_4.json"]
path_to_jsons = "taskDescriptors/sweep3"
list_task_per_request = [16, 32, 64, 128]

for json_file in list_of_jsons_to_edit:
    json_path = os.path.join(path_to_jsons, json_file)

    # Read original JSON
    with open(json_path, 'r') as f:
        data = json.load(f)

    for task_count in list_task_per_request:
        # Copy and update the data
        modified_data = data.copy()

        if "tasksMoveCount" in modified_data:
            modified_data["tasksMoveCount"] = task_count
        else:
            print(f"'tasksMoveCount' not found in {json_file}. Skipping task count {task_count}.")
            continue

        # Generate new filename
        file_base, _ = os.path.splitext(json_file)
        new_filename = f"{file_base}_{task_count}.json"
        new_path = os.path.join(path_to_jsons, new_filename)

        # Save modified file
        with open(new_path, 'w') as f:
            json.dump(modified_data, f, indent=4)

        print(f"Saved: {new_filename}")
