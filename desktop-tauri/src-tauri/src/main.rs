#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
mod store;
use store::{Workspace, WorkspaceStore};
use tauri::Manager;
struct StoreState(Result<WorkspaceStore, String>);
#[tauri::command]
async fn load_workspace(store: tauri::State<'_, StoreState>) -> Result<Workspace, String> {
    store
        .inner()
        .0
        .as_ref()
        .map_err(|error| error.clone())?
        .load()
}
#[tauri::command]
async fn save_workspace(
    workspace: Workspace,
    store: tauri::State<'_, StoreState>,
) -> Result<Workspace, String> {
    store
        .inner()
        .0
        .as_ref()
        .map_err(|error| error.clone())?
        .save(workspace)
}
fn main() {
    tauri::Builder::default()
        .setup(|app| {
            let directory = if cfg!(debug_assertions) {
                std::env::var_os("ZHUPI_WORKBENCH_TEST_DIR")
                    .map(std::path::PathBuf::from)
                    .unwrap_or(app.path().app_data_dir()?)
            } else {
                app.path().app_data_dir()?
            };
            app.manage(StoreState(WorkspaceStore::open(directory)));
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![load_workspace, save_workspace])
        .run(tauri::generate_context!())
        .expect("Unable to start desktop writing prototype");
}
