import { AirGapWalletStatus } from '@airgap/coinlib-core'
import { Component, Input, OnInit } from '@angular/core'
import { ErrorCategory, handleErrorLocal } from 'src/app/services/error-handler/error-handler.service'
import { NavigationService } from 'src/app/services/navigation/navigation.service'
import { LifehashService } from 'src/app/services/lifehash/lifehash.service'
import { SecretsService } from 'src/app/services/secrets/secrets.service'

import { MnemonicSecret } from '../../models/secret'
import { AdvancedModeType, VaultStorageKey, VaultStorageService } from 'src/app/services/storage/storage.service'
import { Observable } from 'rxjs'
import { map } from 'rxjs/operators'

@Component({
  selector: 'airgap-secret-item',
  templateUrl: './secret-item.component.html',
  styleUrls: ['./secret-item.component.scss']
})
export class SecretItemComponent implements OnInit {
  @Input()
  public secret: MnemonicSecret

  public activeWallets: string[]
  public hasMoreWallets: number = 0

  public lifehashData: string = ''

  public isAdvancedMode$: Observable<boolean> = this.storageService
    .subscribe(VaultStorageKey.ADVANCED_MODE_TYPE)
    .pipe(map((res) => res === AdvancedModeType.ADVANCED))

  constructor(
    private readonly secretsService: SecretsService,
    public navigationService: NavigationService,
    private readonly lifehashService: LifehashService,
    private readonly storageService: VaultStorageService
  ) {}

  public async ngOnInit() {
    this.secretsService.getActiveSecretObservable().subscribe((secret: MnemonicSecret) => {
      if (secret && secret.wallets) {
        this.getWalletsFromSecret()
      }
    })

    this.getWalletsFromSecret()

    this.lifehashData = await this.lifehashService.generateLifehash(this.secret.fingerprint)
  }

  public getWalletsFromSecret() {
    this.activeWallets = this.secret.wallets
      .filter((wallet) => wallet.status === AirGapWalletStatus.ACTIVE)
      .sort((a, b) => a.protocol.name.localeCompare(b.protocol.name)) // TODO: Use same order as common lib
      .map((wallet) => wallet.protocol.symbol)

    if (this.activeWallets.length > 10) {
      this.hasMoreWallets = this.activeWallets.length - 10
      this.activeWallets = this.activeWallets.slice(0, 10)
    } else {
      this.hasMoreWallets = 0
    }
  }

  public goToEditSecret(ev: TouchEvent): void {
    ev.stopPropagation()
    this.navigationService.routeWithState('/secret-edit', { secret: this.secret }).catch(handleErrorLocal(ErrorCategory.IONIC_NAVIGATION))
  }

  public goToAccountsList(): void {
    this.navigationService.routeWithState('/accounts-list', { secret: this.secret }).catch(handleErrorLocal(ErrorCategory.IONIC_NAVIGATION))
  }
}
