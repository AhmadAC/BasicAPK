import pyperclip
from pathlib import Path
# os was mentioned as potentially used by an external 'controller.py',
# but is not used in this script. Pathlib handles path operations.

# --- Configuration ---
# Set to False to only process files in the root of the target_folder (Folder Mode).
# Set to True to include files in subdirectories as well (Folder Mode).
INCLUDE_SUBDIRECTORIES = True

# Add names of subdirectories to ignore (Folder Mode). Case-sensitive.
# If a file is found within any of these subdirectories (relative to target_folder),
# it will be skipped. This applies if INCLUDE_SUBDIRECTORIES is True.
# Example: ["venv", ".venv", "__pycache__", "node_modules", ".git", "build", "dist", "docs_old"]
EXCLUDED_SUBDIRECTORIES = ["venv", ".venv", "__pycache__", "tests_to_ignore", "maps", ".git", "build", "dist", "node_modules"]

# File extensions for which content will be read and included in the output.
# Other files will be listed, but their content will not be included.
# Ensure extensions start with a dot, e.g., ".txt". Case-insensitive matching.
CONTENT_EXTENSIONS_TO_READ = [".java", ".yml", ".xml", ".gradle", ".properties"]

# Specific filenames to exclude from processing. Case-insensitive for the full filename.
# This list should contain lowercase filenames.
EXCLUDED_FILENAMES_LOWER = ["merged.txt", "tempcoderunnerfile.py"] # merged.txt is the typical output file
# --- End Configuration ---

# --- Constants ---
MERGED_OUTPUT_FILENAME = "merged.txt" # Default name for the output file
FILE_CONTENT_SEPARATOR = "=" * 80
FILE_START_MARKER_CHAR = "#"
FILE_START_MARKER_COUNT = 20
ERROR_MARKER_CHAR = "!"
ERROR_MARKER_COUNT = 20
# --- End Constants ---

# --- Helper Functions (General Purpose) ---
def _clean_path_str(s: str) -> str:
    """Removes leading/trailing whitespace and surrounding quotes from a string."""
    s = s.strip()
    if (s.startswith('"') and s.endswith('"')) or \
       (s.startswith("'") and s.endswith("'")):
        s = s[1:-1]
    return s

def get_display_path(file_path: Path, base_path: Path) -> str:
    """
    Generates a display string for a file path.
    Tries to make it relative to base_path, otherwise returns the absolute path.
    """
    try:
        # Ensure base_path is absolute for robust relative_to behavior
        # if base_path itself might be relative.
        # However, in this script, base_path is usually derived from existing paths
        # and should be effectively absolute or resolve correctly.
        return str(file_path.relative_to(base_path))
    except ValueError:
        # This can happen if file_path is not under base_path (e.g. different drive on Windows)
        return str(file_path.resolve()) # Absolute path if not relative

# --- Helper Functions (Core Logic for File Collection) ---
def _collect_all_files_from_folder(
    folder_path: Path, include_subdirs: bool, excluded_subdirs_list: list[str],
    excluded_filenames_lower_list: list[str]
) -> list[Path]:
    """
    Scans a folder for all files, applying exclusions.
    This is used for "Folder Mode".
    """
    collected_files = []
    script_path_resolved = Path(__file__).resolve() # For self-exclusion

    print(f"Searching for all files in {folder_path}...")
    if include_subdirs:
        iterator = folder_path.rglob("*") # Changed from "*.py"
        print("  (Including subdirectories)")
    else:
        iterator = folder_path.glob("*") # Changed from "*.py"
        print("  (Root directory only)")

    for item_path in iterator:
        if not item_path.is_file(): # Skip directories
            continue
            
        # Determine display path for messages (try relative, fallback to absolute)
        try:
            # Try to make path relative to the initial folder_path for consistent display
            display_item_path_str = str(item_path.relative_to(folder_path))
        except ValueError:
            display_item_path_str = str(item_path.resolve()) # Fallback to absolute if not relative
            
        # Self-exclusion: Skip the script file itself
        if item_path.resolve() == script_path_resolved:
            print(f"  Skipping (self): {display_item_path_str}")
            continue
            
        # Filename exclusion
        if item_path.name.lower() in excluded_filenames_lower_list:
            print(f"  Skipping (excluded by name '{item_path.name}'): {display_item_path_str}")
            continue
        
        # Subdirectory exclusion (only if include_subdirs is True)
        if include_subdirs and excluded_subdirs_list:
            try:
                # For subdir exclusion, path MUST be relative to folder_path.
                # If item_path.relative_to(folder_path) fails, it's not in a subfolder of folder_path.
                relative_item_path_for_subdir_check = item_path.relative_to(folder_path)
                is_in_excluded_dir = False
                # Check if any parent directory component of the relative path is in the exclusion list
                for dir_component in relative_item_path_for_subdir_check.parent.parts:
                    if dir_component in excluded_subdirs_list:
                        print(
                            f"  Skipping (in excluded subdir '{dir_component}'): {relative_item_path_for_subdir_check}"
                        )
                        is_in_excluded_dir = True
                        break
                if is_in_excluded_dir:
                    continue
            except ValueError:
                # Path is not relative to folder_path, so it's not in one of its subdirectories.
                # This can happen for symlinks pointing outside folder_path.
                pass # Continue, as this specific exclusion condition is not met.

        collected_files.append(item_path)
    return collected_files

# --- Helper Functions (Main Workflow Steps) ---
def _get_raw_paths_from_clipboard() -> list[str] | None:
    """Reads and cleans potential path strings from the clipboard."""
    try:
        clipboard_content = pyperclip.paste()
        if not clipboard_content:
            print("Error: Clipboard is empty.")
            return None
        print(f"Read from clipboard:\n---\n{clipboard_content}\n---")
    except pyperclip.PyperclipException as e:
        print(f"Error: pyperclip could not access the clipboard: {e}")
        print("Please ensure a copy/paste mechanism is installed (e.g., xclip, xsel on Linux).")
        return None
    except Exception as e: # pylint: disable=broad-except
        print(f"An unexpected error occurred with pyperclip (paste): {e}")
        return None

    lines = clipboard_content.strip().splitlines()
    potential_path_strs = [_clean_path_str(line) for line in lines if _clean_path_str(line)]

    if not potential_path_strs:
        print("Error: Clipboard contained no parsable paths after cleaning.")
        return None
    return potential_path_strs

def _determine_files_and_output_dir(
    potential_path_strs: list[str],
    configured_excluded_filenames: list[str] 
) -> tuple[list[Path] | None, Path | None, str | None]:
    """
    Determines the processing mode, collects files, and sets the output directory.
    Returns (list_of_files, output_base_directory, mode_name) 
    or (None, None, None) on critical error.
    If no files are found but paths are valid, returns ([], valid_output_dir, mode_name).
    """
    files_to_process: list[Path] = []
    output_base_directory: Path | None = None
    processing_mode_name: str = "unknown"
    script_path_resolved = Path(__file__).resolve()
    
    # Prepare a list of lowercase filenames for exclusion, including the standard output file.
    current_excluded_filenames_lower = [fn.lower() for fn in configured_excluded_filenames]
    if MERGED_OUTPUT_FILENAME.lower() not in current_excluded_filenames_lower:
        current_excluded_filenames_lower.append(MERGED_OUTPUT_FILENAME.lower())

    if len(potential_path_strs) == 1:
        single_path_str = potential_path_strs[0]
        p = Path(single_path_str)
        if not p.exists():
            print(f"Error: Path '{single_path_str}' does not exist.")
            return None, None, None

        if p.is_dir():
            processing_mode_name = "folder"
            target_folder = p
            output_base_directory = target_folder
            print(f"\nProcessing Mode: Folder ({target_folder})")
            print(f"Configuration: Include subdirectories = {INCLUDE_SUBDIRECTORIES}")
            if EXCLUDED_SUBDIRECTORIES:
                print(f"Configuration: Ignoring subdirectories named = {', '.join(EXCLUDED_SUBDIRECTORIES)}")
            else:
                print("Configuration: No subdirectories explicitly configured for exclusion.")
            if current_excluded_filenames_lower:
                 print(f"Configuration: Ignoring filenames (case-insensitive) = {', '.join(current_excluded_filenames_lower)}")
            
            files_to_process = _collect_all_files_from_folder(
                target_folder, INCLUDE_SUBDIRECTORIES, EXCLUDED_SUBDIRECTORIES, current_excluded_filenames_lower
            )
        elif p.is_file():
            # Check for self-exclusion or excluded filename for the single file
            if p.resolve() == script_path_resolved:
                print(f"Error: The provided file path '{single_path_str}' is this script itself. Nothing to process.")
                # Return empty list, but valid parent dir, and mode name
                return [], p.parent, "single_file_self_skip" 
            if p.name.lower() in current_excluded_filenames_lower:
                print(f"Error: The provided file path '{single_path_str}' ('{p.name}') is an excluded filename. Nothing to process.")
                return [], p.parent, "single_file_excluded_skip"

            processing_mode_name = "single_file"
            files_to_process = [p]
            output_base_directory = p.parent
            print(f"\nProcessing Mode: Single File ({get_display_path(p, p.parent if p.parent else Path('.'))})")
        else:
            print(f"Error: Clipboard item '{single_path_str}' is not a valid "
                  "directory or an existing file.")
            return None, None, None
    else:  # Multiple lines/paths from clipboard
        processing_mode_name = "multiple_files"
        print(f"\nProcessing Mode: Multiple Files (found {len(potential_path_strs)} potential paths)")
        temp_files_list = []
        # Store paths that are at least files for Output Directory Determination (ODD)
        # even if they are later excluded.
        paths_considered_for_odt = [] 

        for path_str in potential_path_strs:
            p_candidate = Path(path_str)
            if p_candidate.is_file(): # .is_file() implies .exists()
                paths_considered_for_odt.append(p_candidate) # Add to ODT list before further exclusions
                
                if p_candidate.resolve() == script_path_resolved:
                    print(f"  Skipping (self): {path_str}")
                    continue
                if p_candidate.name.lower() in current_excluded_filenames_lower:
                    print(f"  Skipping (excluded by name '{p_candidate.name}'): {path_str}")
                    continue
                
                temp_files_list.append(p_candidate)
                # Display path relative to its own parent for conciseness
                print(f"  Will process: {get_display_path(p_candidate, p_candidate.parent if p_candidate.parent else Path('.'))}")
            else: # Path is not a file
                if not p_candidate.exists():
                    print(f"  Skipping: '{path_str}' (path does not exist)")
                elif p_candidate.is_dir(): # Explicitly state if it's a directory being skipped in multi-file mode
                     print(f"  Skipping: '{path_str}' (is a directory; multi-file mode only processes files)")
                else: # Other reasons (e.g. broken symlink, permissions)
                     print(f"  Skipping: '{path_str}' (not a valid file path for processing)")
        
        files_to_process = temp_files_list

        if not files_to_process: # No files left after filtering
            print("Warning: No files to process from the clipboard paths after filtering.")
            # Try to determine output_base_directory from the first valid file path encountered,
            # even if it was subsequently excluded.
            if paths_considered_for_odt: 
                output_base_directory = paths_considered_for_odt[0].parent
                print(f"Output directory for {MERGED_OUTPUT_FILENAME} determined as: {output_base_directory} (though no files will be processed from this list).")
                # Return empty list, but valid dir and mode name
                return [], output_base_directory, processing_mode_name
            else: # No valid file paths at all were found in the input list
                print("Error: No valid file paths found in clipboard to determine an output directory.")
                return None, None, None # Critical error if no base for output can be found
        else: # Files to process exist
            # Determine output_base_directory from the parent of the first file to be processed.
            # This is a heuristic; assumes files are somewhat co-located or this is acceptable.
            output_base_directory = files_to_process[0].parent
            print(f"Output directory for {MERGED_OUTPUT_FILENAME} will be: {output_base_directory}")


    # Final checks after mode-specific logic
    if output_base_directory is None: # Should ideally be set by now if any valid path was processed
        print("Critical Error: Output base directory could not be determined.")
        return None, None, None

    if not files_to_process:
        # This path is reached if Folder Mode yields no files, or Single File mode skips the file.
        print(f"No files found matching the criteria for mode '{processing_mode_name}'.")
        # Return empty list, but valid output_dir and mode_name
        return [], output_base_directory, processing_mode_name 
    
    files_to_process.sort() # Sort for consistent order in listing and processing
    return files_to_process, output_base_directory, processing_mode_name


def _build_merged_content_string(
    all_files_to_process: list[Path], display_base_path: Path,
    content_extensions_lower: list[str] # Expects lowercase extensions with leading dot
) -> str:
    """
    Builds a string containing a list of all files and the content of specified file types.
    """
    print(f"\nFound {len(all_files_to_process)} file(s) to list/process:")
    for file_path in all_files_to_process:
        # Use display_base_path for consistent relative paths in this listing
        print(f"  - {get_display_path(file_path, display_base_path)}")

    # Part 1: Summary header listing ALL processed files
    summary_header_parts = [
        f"Total files found and listed: {len(all_files_to_process)}\n",
        "List of all files found (paths relative to processing context):\n"
    ]
    for file_path in all_files_to_process:
        summary_header_parts.append(f"- {get_display_path(file_path, display_base_path)}\n")
    
    files_with_content_count = 0
    content_section_parts = [] # To hold headers and content of files

    # Part 2: Iterate again to append content for files with specified extensions
    for file_path in all_files_to_process:
        # Match file extension against the list of extensions for content reading
        if file_path.suffix.lower() in content_extensions_lower:
            files_with_content_count += 1
            # Use display_base_path for paths in the content headers
            display_name_in_content = get_display_path(file_path, display_base_path)
            try:
                # Read file content
                with open(file_path, "r", encoding="utf-8", errors="replace") as f:
                    file_content_data = f.read()
                # Create header for this file's content
                header = (
                    f"\n\n{FILE_START_MARKER_CHAR * FILE_START_MARKER_COUNT}"
                    f" START OF FILE: {display_name_in_content} "
                    f"{FILE_START_MARKER_CHAR * FILE_START_MARKER_COUNT}\n\n"
                )
                content_section_parts.append(header)
                content_section_parts.append(file_content_data)
            except (IOError, OSError) as e:
                # Handle errors during file reading
                error_msg_text = (
                    f"\n\n{ERROR_MARKER_CHAR * ERROR_MARKER_COUNT}"
                    f" ERROR READING FILE CONTENT: {display_name_in_content} - {e} "
                    f"{ERROR_MARKER_CHAR * ERROR_MARKER_COUNT}\n\n"
                )
                print(f"  Error reading content from {display_name_in_content}: {e}")
                content_section_parts.append(error_msg_text)
            except Exception as e: # Catch any other unexpected errors
                error_msg_text = (
                    f"\n\n{ERROR_MARKER_CHAR * ERROR_MARKER_COUNT}"
                    f" UNEXPECTED ERROR PROCESSING FILE CONTENT: {display_name_in_content} - {e} "
                    f"{ERROR_MARKER_CHAR * ERROR_MARKER_COUNT}\n\n"
                )
                print(f"  Unexpected error for {display_name_in_content} content: {e}")
                content_section_parts.append(error_msg_text)
    
    # Add information about content inclusion to the summary
    if files_with_content_count > 0:
        if content_extensions_lower: # If there were extensions specified for reading
             summary_header_parts.append(f"\nContent included for {files_with_content_count} file(s) with extensions: {', '.join(content_extensions_lower)}\n")
        else: # Should ideally not happen if files_with_content_count > 0, but as a fallback
             summary_header_parts.append(f"\nContent included for {files_with_content_count} file(s).\n")
        # Add separator only if there's actual content to follow
        summary_header_parts.append("\n" + FILE_CONTENT_SEPARATOR + "\n")
        # Combine summary and content sections
        all_output_parts = ["".join(summary_header_parts)] 
        all_output_parts.extend(content_section_parts)
    else: # No content was read for any file
        if content_extensions_lower: # Extensions were specified, but no files matched
            summary_header_parts.append(f"\nNo files found with extensions matching ({', '.join(content_extensions_lower)}) for content inclusion.\n")
        else: # No extensions were specified for content reading
            summary_header_parts.append(f"\nNo file content was configured to be included (CONTENT_EXTENSIONS_TO_READ is empty or invalid).\n")
        # Only summary part will be in the output
        all_output_parts = ["".join(summary_header_parts)]

    return "".join(all_output_parts)

def _write_to_file_and_clipboard(content_str: str, output_base_dir: Path) -> None:
    """Saves the collated content to a file and copies it to the clipboard."""
    file_saved_successfully = False
    # Construct the full path for the output file
    merged_file_path = output_base_dir / MERGED_OUTPUT_FILENAME

    try:
        # Ensure the output directory exists
        output_base_dir.mkdir(parents=True, exist_ok=True)
    except (IOError, OSError) as e:
        print(f"Error: Could not create or access output directory {output_base_dir}: {e}")
        print(f"{MERGED_OUTPUT_FILENAME} will not be saved to a file.")
    else:
        # Try to write the content to the file
        try:
            with open(merged_file_path, "w", encoding="utf-8") as outfile:
                outfile.write(content_str)
            # Use resolve() for a clear, absolute path in the success message
            print(f"\nSuccessfully generated report into: {merged_file_path.resolve()}")
            file_saved_successfully = True
        except (IOError, OSError) as e:
            print(f"\nError writing content to {merged_file_path.resolve()}: {e}")
        except Exception as e: # Catch any other unexpected errors during file writing
            print(f"\nUnexpected error writing content to {merged_file_path.resolve()}: {e}")

    # Try to copy the content to the clipboard
    try:
        pyperclip.copy(content_str)
        if file_saved_successfully:
            print(f"Full content (also in {merged_file_path.name}) has been copied to the clipboard.")
        else:
            # Message if file saving failed but clipboard copy succeeded
            print(f"\nFull content (only) copied to clipboard due to file save error for {merged_file_path.name}.")
    except pyperclip.PyperclipException as e:
        # Handle errors related to clipboard access
        print(f"Error: Could not copy content to clipboard: {e}")
        print("Please ensure a copy/paste mechanism is installed (e.g., xclip, xsel on Linux).")
    except Exception as e: # Catch any other unexpected errors during clipboard copy
        print(f"An unexpected error occurred while copying content to clipboard: {e}")

# --- Main Public Function ---
def collate_files_from_clipboard():
    """
    Main orchestrator: Reads path(s) from clipboard, lists all found files,
    merges content of specified file types, saves to an output file, and copies to clipboard.
    """
    print("--- File Lister and Content Collator Script ---")
    raw_paths = _get_raw_paths_from_clipboard()
    if raw_paths is None: # Error occurred or clipboard empty/unparsable
        return

    # Determine files to process and output directory based on clipboard input
    files_to_process, output_dir, mode_name = _determine_files_and_output_dir(
        raw_paths,
        EXCLUDED_FILENAMES_LOWER # Pass the configured list of excluded filenames
    )

    # Handle critical errors from file determination step
    if files_to_process is None or output_dir is None:
        print("Aborting due to critical error in path processing or output directory determination.")
        return
    
    # Handle cases where no files are to be processed (e.g., empty folder, all files excluded)
    if not files_to_process:
        # A message should have been printed by _determine_files_and_output_dir
        print(f"No files to list or process based on mode '{mode_name}'. Output file '{MERGED_OUTPUT_FILENAME}' will not be created. Exiting.")
        return

    # Prepare list of lowercase extensions for content reading, ensuring they are valid (start with '.')
    content_extensions_lower = [
        ext.lower() for ext in CONTENT_EXTENSIONS_TO_READ if isinstance(ext, str) and ext.startswith(".")
    ]

    # Build the collated string (list of files + content of specified types)
    collated_content_str = _build_merged_content_string(files_to_process, output_dir, content_extensions_lower)
    
    # Write to file and copy to clipboard
    _write_to_file_and_clipboard(collated_content_str, output_dir)
    
    print("--- Collation process complete ---")

# --- Alias for external compatibility (if script is named ReadCode.py) ---
# This addresses a potential issue if an external script tries to call a specific function name.
# The original alias was 'merge_python_files_from_clipboard_path'.
# Keeping the alias name as it might be expected by an external controller, but pointing to the new main function.
merge_python_files_from_clipboard_path = collate_files_from_clipboard

# --- Script Execution ---
if __name__ == "__main__":
    # To test:
    # 1. Copy a folder path to your clipboard.
    # 2. Or, copy a single file path to your clipboard.
    # 3. Or, copy multiple file paths (one per line, optionally quoted) to your clipboard.
    # Then, run this script.
    collate_files_from_clipboard()
    # input("\nPress Enter to exit...") # Uncomment if running from a terminal that closes immediately