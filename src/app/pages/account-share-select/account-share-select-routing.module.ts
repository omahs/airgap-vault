import { NgModule } from '@angular/core'
import { Routes, RouterModule } from '@angular/router'

import { AccountShareSelectPage } from './account-share-select.page'

const routes: Routes = [
  {
    path: '',
    component: AccountShareSelectPage
  }
]

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule]
})
export class AccountShareSelectRoutingModule {}
